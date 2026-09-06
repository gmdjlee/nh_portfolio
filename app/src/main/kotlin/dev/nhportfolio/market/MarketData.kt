package dev.nhportfolio.market

import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.api.loadResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

/** 유니버스 조달 경로 — 코스피200 ETF 구성종목. krstock 에 랭킹 API가 없어 이 ETF 를 대신 쓴다. */
private const val KODEX200 = "069500"

/** 시장 대용 지수 — 같은 069500 을 이번엔 유니버스 원천이 아니라 "시장이 어떻게 움직였는가"의
 *  대용 종가로 받는다(사양 §4.3). 코스피 원계열이 NH 에 없어 이 ETF 값으로 대신한다. */
private const val INDEX_CODE = "069500"

/** 캐시가 없는 종목의 최초 백필 일수. */
private const val BACKFILL_DAYS = 1100

/** 유니버스를 다시 받기까지 허용하는 최대 경과일. */
private const val UNIVERSE_MAX_AGE_DAYS = 90L

/** 재개 시 여유분. 달력일이 거래일보다 항상 많으니 이만큼 더 받아도 병합이 중복만 정리하고 빈틈은 안 남는다. */
private const val GAP_MARGIN_DAYS = 5

/** 동시에 갱신할 종목 수. 요청 간격은 [NhApi] 의 전역 게이트가 지키므로 여기서는 동시
 *  개수만 제한하면 된다(합산 속도는 여전히 초당 요청 제한 아래로 묶인다). */
private const val SYNC_LANES = 4

/** `track.json` 행 검증에 쓰는 목표 비중 상한(bp). 하한은 0. */
private const val FULL_BP = 10_000

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
private val DATE_REGEX = Regex("^\\d{8}$")

/** [DATE_REGEX] 형식(8자리 숫자)이면서 실제 달력에 있는 날짜인지 — 13월처럼 자릿수만 맞는 값을 걸러낸다. */
private fun String.isCalendarDate(): Boolean = DATE_REGEX.matches(this) && runCatching { LocalDate.parse(this, DATE_FMT) }.isSuccess

sealed interface SyncState {
    data object Idle : SyncState

    data class Running(
        val done: Int,
        val total: Int,
    ) : SyncState

    data class Done(
        val failed: Int,
    ) : SyncState
}

/** `dir/universe.json`. [at] 은 유니버스를 받은 날(YYYYMMDD). */
@Serializable
private data class UniverseFile(
    val at: String,
    val codes: List<String>,
)

/** `dir/bars/<code>.json`. [dates]·[closes] 는 일자 오름차순 병렬 배열, [ex] 는 권리락일만 기준가를 담는다. */
@Serializable
private data class BarsFile(
    val dates: List<String>,
    val closes: List<Int>,
    val ex: Map<String, Int> = emptyMap(),
)

/** `dir/track.json` 행 하나(사양 §4.1). [band] 는 [Band] 이름 문자열로 저장한다 — 다시 읽을 때
 *  [Obs] 로는 옮기지 않지만(판정은 모델 자신의 규칙으로 재구성한다, 사양 §4.1) 기록 자체는 남긴다. */
@Serializable
private data class TrackRow(
    val asOf: String,
    val computedOn: String,
    val targetBp: Int,
    val band: String,
    val score: Double,
    val breadth: Double,
    val pctile: Double,
    val window: Int,
    val universeAt: String,
    val universeSize: Int,
)

/** `dir/track.json` 전체. */
@Serializable
private data class TrackFile(
    val rows: List<TrackRow> = emptyList(),
)

/**
 * 화면이 필요로 하는 모든 것. 캐시를 한 번만 읽는다. [universeAt] 은 `universe.json` 의 `at`
 * ("" 는 유니버스가 없다는 뜻)이다 — 소급 모드에서 화면이 [Signal] 을 [Obs] 로 바꿀 때
 * `universeAt` 자리를 이 값으로 채운다(사양 §4.5, "오늘의 유니버스 기준" 라벨의 근거).
 */
data class TrackData(
    val market: Market,
    val retro: List<Signal?>,
    val obs: List<Obs>,
    val universeAt: String,
)

/**
 * 종가 캐시와 동기화. 저장은 종목당 파일 하나다(컨트롤러 판단) — 200종목 백필 도중 죽어도
 * 그 순간 쓰던 파일 하나만 위험하고 나머지는 멀쩡해서, 재개·부분실패·손상 격리가 전부 공짜다.
 * [dir] 는 캐시 루트: `dir/universe.json`, `dir/bars/<code>.json`, `dir/index/069500.json`
 * (시장 대용 지수, 사양 §4.3), `dir/track.json`(관측 기록, 사양 §4.1) 을 둔다.
 */
class MarketData(
    private val api: NhApi,
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 캐시된 종가로 계산한 신호. 캐시가 없거나 모자라면 null. 네트워크를 타지 않는다. */
    fun cached(): Signal? {
        val (calendar, closes) = loadCalendar()
        return Breadth.signal(closes, calendar)
    }

    /** 확보한 거래일 수. 신호가 null 일 때 "거래일 N, 최소 약 500일" 표시에 쓴다. */
    fun cachedDays(): Int = loadCalendar().first.size

    /**
     * 효용성 화면이 필요로 하는 모든 것을 한 번에 만든다. [cached] 와 마찬가지로 파일을
     * 읽으므로 호출부(뷰모델)가 메인 스레드가 아닌 곳에서 불러야 한다.
     *
     * **정렬 계약**: 이 함수 안에서 [loadCalendar] 를 딱 한 번만 불러 그 결과(달력·종가)
     * 하나로 [Market.dates]·[Market.equal]·[retro] 를 모두 만든다 — 셋의 길이는 항상
     * `market.dates.size` 로 같다. [Market.index] 도 null 이 아니면 마찬가지 길이다 —
     * 화면은 세 배열을 같은 인덱스로 나란히 읽을 수 있다는 뜻이다. 유니버스가 없으면
     * [loadCalendar] 가 빈 달력을 주므로 [Market.dates]·[retro]·[Market.equal] 은 모두
     * 비고 [Market.index] 는 null 이다 — 그래도 관측 기록([obs])만은 유니버스와 무관하게
     * 그대로 돌려준다.
     */
    fun trackData(): TrackData {
        val (calendar, closes) = loadCalendar()
        val universe = readJson<UniverseFile>(universeFile())
        val market =
            Market(
                dates = calendar,
                index = alignedIndex(calendar),
                equal = Track.equalWeight(closes, calendar.size),
            )
        return TrackData(
            market = market,
            retro = Breadth.series(closes, calendar),
            obs = readObs(),
            universeAt = universe?.at.orEmpty(),
        )
    }

    /**
     * 유니버스와 종가를 갱신한다. 진행률을 흘리고, 끝나면 새 신호를 돌려준다(콜드 플로우).
     * 종목마다 [SYNC_LANES] 개까지 동시에 처리한다 — cts/edate 페이징이 줄어든 데다(NhApi.dailyBars)
     * 요청 간격도 전역 게이트 하나로 묶였으니, 남은 병목은 종목 수만큼의 왕복 지연뿐이다.
     * 수집자가 취소되면(화면 이탈) 구조적 동시성으로 아직 도는 종목 코루틴도 함께 취소된다.
     */
    fun sync(): Flow<SyncState> =
        channelFlow {
            val today = LocalDate.now().format(DATE_FMT)
            val cachedUniverse = readJson<UniverseFile>(universeFile())

            // at 이 YYYYMMDD 로 파싱되지 않으면(구조는 멀쩡한 손상) 없는 것과 같이 갱신 대상으로 본다 —
            // 파싱 실패를 그냥 던지면 화면이 죽는다(§"파일이 깨지면 없는 것으로 본다"가 여기도 적용된다).
            val codes =
                if (cachedUniverse == null ||
                    runCatching { daysBetween(cachedUniverse.at, today) }.getOrDefault(Long.MAX_VALUE) >= UNIVERSE_MAX_AGE_DAYS
                ) {
                    // 빈 응답도 실패와 똑같이 다룬다 — 그대로 밀어붙이면 캐시된 종목의 봉 파일이 전부
                    // 지워지고, 빈 유니버스가 오늘 날짜로 저장돼 90일간 스스로 회복하지 못한다.
                    loadResult { api.etfComponents(KODEX200) }
                        .mapCatching { fetched -> fetched.ifEmpty { throw NhException("EMPTY", "유니버스 응답이 비어 있습니다") } }
                        .fold(
                            onSuccess = { fetched ->
                                val removed = cachedUniverse?.codes.orEmpty().toSet() - fetched.toSet()
                                writeJson(universeFile(), UniverseFile(at = today, codes = fetched))
                                removed.forEach { barsFile(it).delete() }
                                fetched
                            },
                            onFailure = { e -> cachedUniverse?.codes?.takeIf { it.isNotEmpty() } ?: throw e },
                        )
                } else {
                    cachedUniverse.codes
                }

            // 지수 대용(069500) 은 종목과 같은 방식으로 받되 index/ 에 따로 쓴다 — universe.codes 에는
            // 절대 섞지 않는다(시장폭 종목 수·유니버스 교체 시 삭제 대상 목록이 모두 그 목록만 본다).
            val targets = codes.map { it to barsFile(it) } + (INDEX_CODE to indexFile())
            send(SyncState.Running(0, targets.size))

            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val gate = Semaphore(SYNC_LANES)
            coroutineScope {
                targets.forEach { (code, file) ->
                    launch {
                        gate.withPermit {
                            if (!syncFile(code, file, today)) failed.incrementAndGet()
                            send(SyncState.Running(done.incrementAndGet(), targets.size))
                        }
                    }
                }
            }

            send(SyncState.Done(failed.get()))
        }

    /**
     * 종목(또는 지수) 하나의 캐시를 오늘 날짜까지 채운다. 이미 오늘 것까지 있으면 통신하지
     * 않는다. [file] 을 받아 종목(`bars/`)과 지수(`index/`) 양쪽에서 그대로 재사용한다 —
     * sync() 의 순환복잡도를 낮추려 몸통만 뺀 것이고 새 책임은 없다. 실패하면 false.
     */
    private suspend fun syncFile(
        code: String,
        file: File,
        today: String,
    ): Boolean {
        val existing = readBars(file)
        val lastDate = existing?.dates?.lastOrNull()
        if (lastDate == today) return true
        val count =
            if (lastDate == null) {
                BACKFILL_DAYS
            } else {
                // coerceIn 하한 1 — 기기 시계가 과거로 돌아가 lastDate 가 today 보다 미래로
                // 남아 있으면 daysBetween 이 음수라 count 가 0 이하로 떨어질 수 있다.
                (daysBetween(lastDate, today).toInt() + GAP_MARGIN_DAYS).coerceIn(1, BACKFILL_DAYS)
            }
        return loadResult { api.dailyBars(code, count) }
            .onSuccess { bars -> writeJson(file, merge(existing, bars)) }
            .isSuccess
    }

    /**
     * 관측 기록. `asOf` 별로 한 행이다 — 없으면 추가하고, 있으면 그 행의 `computedOn` 이
     * 오늘일 때만 교체한다(같은 날 재계산·부분 실패 뒤 재갱신 복구용). 다른 날 쓰인 행은
     * 절대 건드리지 않는다(쓰기 자체를 하지 않는다) — 그날 화면이 사용자에게 보여준 값을
     * 지키는 규칙이다(분기 유니버스 교체 뒤 같은 `asOf` 를 다시 계산해도 과거 행이 새
     * 유니버스 값으로 바뀌지 않는다). [today] 는 호출부(화면)가 넘긴다 — 이 파일은 시계를
     * 보지 않는다.
     */
    fun record(
        signal: Signal,
        today: String,
    ) {
        val universe = readJson<UniverseFile>(universeFile())
        val rows = readJson<TrackFile>(trackFile())?.rows.orEmpty()
        val idx = rows.indexOfFirst { it.asOf == signal.asOf }
        if (idx >= 0 && rows[idx].computedOn != today) return

        val row =
            TrackRow(
                asOf = signal.asOf,
                computedOn = today,
                targetBp = signal.targetBp,
                band = signal.band.name,
                score = signal.score,
                breadth = signal.breadth,
                pctile = signal.pctile,
                window = signal.window,
                universeAt = universe?.at.orEmpty(),
                universeSize = universe?.codes?.size ?: 0,
            )
        val updated = if (idx >= 0) rows.toMutableList().apply { set(idx, row) } else rows + row
        writeJson(trackFile(), TrackFile(updated))
    }

    private fun universeFile() = File(dir, "universe.json")

    private fun barsFile(code: String) = File(dir, "bars/$code.json")

    private fun indexFile() = File(dir, "index/$INDEX_CODE.json")

    private fun trackFile() = File(dir, "track.json")

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    /** 임시 파일에 쓰고 renameTo 로 바꾼다 — 도중에 죽어도 기존 파일은 그대로 남는다. */
    private inline fun <reified T> writeJson(
        file: File,
        value: T,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(value))
        tmp.renameTo(file)
    }

    /**
     * dates·closes 길이가 어긋나거나(부분 기록), 종가에 0 이하가 섞이거나, dates 중 하나라도
     * 달력에 없는 날짜(YYYYMMDD 8자리 형식은 맞지만 13월처럼 무효한 값 포함)면(손상) 없는
     * 파일로 본다 — 조용히 다시 받는다. 여기서 걸러야 손상된 일자·종가가 병합을 거쳐 파일에
     * 그대로 남는 일 없이 스스로 회복된다. [file] 을 받으므로 종목(`bars/`)과 지수(`index/`)
     * 양쪽에 같은 검증을 쓴다.
     */
    private fun readBars(file: File): BarsFile? =
        readJson<BarsFile>(file)
            ?.takeIf {
                it.dates.size == it.closes.size &&
                    it.closes.all { c -> c > 0 } &&
                    it.dates.all { d -> d.isCalendarDate() }
            }

    private fun BarsFile.toBars(): List<Bar> =
        dates.indices.map { i ->
            val ref = ex[dates[i]]
            Bar(date = dates[i], close = closes[i], refPrice = ref ?: 0, exRight = ref != null)
        }

    /**
     * [bars] 를 수정주가 보정한 뒤 [calendar] 에 맞춰 정렬한다. 없는 날은 직전 값으로 채우고,
     * 첫 일자 이전(상장 전)은 채우지 않고 0 으로 둔다 — [Breadth] 가 0 을 "그 날 데이터 없음"으로 읽는다.
     */
    private fun alignedCloses(
        bars: BarsFile,
        calendar: List<String>,
    ): IntArray {
        val adjusted = Breadth.adjust(bars.toBars())
        val byDate = HashMap<String, Int>(bars.dates.size * 2)
        bars.dates.forEachIndexed { i, date -> byDate[date] = adjusted[i] }
        val result = IntArray(calendar.size)
        var last = 0
        var started = false
        for (i in calendar.indices) {
            byDate[calendar[i]]?.let {
                last = it
                started = true
            }
            result[i] = if (started) last else 0
        }
        return result
    }

    // ponytail: cached() 를 부를 때마다 유니버스 전 종목의 JSON 을 다시 읽고 파싱한다 — 화면
    // 진입 빈도로는 감당되는 비용이라 별도 캐시를 두지 않는다. 무거워지면 이진 포맷으로 승격.
    private fun loadCalendar(): Pair<List<String>, Map<String, IntArray>> {
        val universe = readJson<UniverseFile>(universeFile()) ?: return emptyList<String>() to emptyMap<String, IntArray>()
        val perStock = universe.codes.mapNotNull { code -> readBars(barsFile(code))?.let { code to it } }
        val calendar = perStock.flatMap { it.second.dates }.distinct().sorted()
        val closes = perStock.associate { (code, bars) -> code to alignedCloses(bars, calendar) }
        return calendar to closes
    }

    /**
     * `track.json` 을 읽어 행 단위로 검증한다. 날짜(asOf·computedOn)는 8자리 달력일,
     * universeAt 은 비어 있거나 8자리 달력일, targetBp 는 0~10000, score 는 0~1, window 는
     * 0 이상이어야 한다 — 하나라도 벗어나면 그 행만 버리고 나머지는 살린다. 파일 자체가
     * 깨졌으면(JSON 파싱 실패) [readJson] 이 이미 null 을 주므로 빈 기록으로 본다.
     */
    private fun readObs(): List<Obs> {
        val rows = readJson<TrackFile>(trackFile())?.rows.orEmpty()
        return rows
            .filter { row ->
                row.asOf.isCalendarDate() &&
                    row.computedOn.isCalendarDate() &&
                    (row.universeAt.isEmpty() || row.universeAt.isCalendarDate()) &&
                    row.targetBp in 0..FULL_BP &&
                    row.score in 0.0..1.0 &&
                    row.window >= 0
            }.map { row ->
                Obs(
                    asOf = row.asOf,
                    computedOn = row.computedOn,
                    targetBp = row.targetBp,
                    score = row.score,
                    window = row.window,
                    universeAt = row.universeAt,
                )
            }
    }

    /**
     * 069500 종가를 [calendar] 에 맞춰 정렬하고 첫 정의일을 1.0 으로 정규화한다(화면이 100
     * 으로 재조정한다). 파일이 없거나 손상됐으면, 또는 [calendar] 와 겹치는 구간이 아예
     * 없으면 null — 화면은 지수 없이 동일가중 평균만으로도 그려야 한다(실기기 확인 전까지의
     * 미지수, 사양 §8).
     */
    private fun alignedIndex(calendar: List<String>): DoubleArray? {
        val bars = readBars(indexFile()) ?: return null
        val aligned = alignedCloses(bars, calendar)
        val firstDefined = aligned.indexOfFirst { it != 0 }
        if (firstDefined < 0) return null
        val base = aligned[firstDefined].toDouble()
        return DoubleArray(calendar.size) { t -> if (t < firstDefined) Double.NaN else aligned[t] / base }
    }

    private fun merge(
        existing: BarsFile?,
        fresh: List<Bar>,
    ): BarsFile {
        val byDate = LinkedHashMap<String, Bar>()
        existing?.toBars()?.forEach { byDate[it.date] = it }
        fresh.forEach { byDate[it.date] = it } // 새로 받은 값이 이긴다
        val sorted = byDate.values.sortedBy { it.date }
        return BarsFile(
            dates = sorted.map { it.date },
            closes = sorted.map { it.close },
            ex = sorted.filter { it.exRight }.associate { it.date to it.refPrice },
        )
    }

    private fun daysBetween(
        a: String,
        b: String,
    ): Long = ChronoUnit.DAYS.between(LocalDate.parse(a, DATE_FMT), LocalDate.parse(b, DATE_FMT))
}
