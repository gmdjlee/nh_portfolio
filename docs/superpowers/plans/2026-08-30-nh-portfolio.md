# NH Portfolio 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** NH투자증권 나무 계좌의 보유 종목·수익률·자산 비중을 보여주고, 목표 비중을 입력하면 매수/매도해야 할 주식 수를 계산해 주는 Android 앱 (거래 기능 없음).

**Architecture:** 단일 Gradle 모듈 `:app`, package-by-feature. 경계는 셋뿐이다 — `api/NhApi.kt`(HTTP·WebSocket·NH JSON을 아는 유일한 파일, DTO는 전부 `private`), `security/`(Keystore·DataStore 비밀을 만지는 유일한 코드), feature 패키지(Screen + ViewModel, NH 필드명을 모름). Repository·UseCase·인터페이스·팩토리는 없다. DataStore가 저장소, `Vault`의 봉인 blob이 유일한 토큰 저장소, `stateIn(WhileSubscribed)`이 라이프사이클, DEK 유무가 잠금 상태다.

**Tech Stack:** Kotlin 2.4.10 · Jetpack Compose (BOM 2026.08.00, Material 3) · Ktor 3.5.2 (REST + WebSocket) · kotlinx.serialization · Koin 4.2.2 · DataStore Preferences · AndroidX Biometric · AGP 9.3.2 / Gradle 9.7.1 / JDK 21

**Spec:** `docs/superpowers/specs/2026-08-30-nh-portfolio-design.md` — 계획은 사양을 근거로 삼는다. 실행자는 둘 다 읽는다. 아래에서 "사양 §N"은 이 문서의 절 번호다.

---

## Global Constraints

모든 태스크의 요구사항에 암묵적으로 포함된다.

- **언어**: Kotlin only. Java 파일·XML 레이아웃 금지 (리소스 XML — manifest/themes/strings/아이콘/`data_extraction_rules` — 은 예외).
- **패키지**: `dev.nhportfolio`. applicationId 동일.
- **SDK**: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 31`. JDK 21 (`JAVA_HOME`은 Android Studio 번들 JBR: `C:\Program Files\Android\Android Studio\jbr`).
- **비밀 취급 (절대 규칙)**: appkey·appsecretkey·access token을 로그·화면·예외 메시지·스택트레이스에 절대 노출하지 않는다. `Log.e`/`Log.w` 호출 0개. `ktor-client-logging` 의존성 자체를 추가하지 않는다. 자격증명을 하드코딩하지 않는다.
- **NH API 규칙**: 토큰은 24시간 캐시하고 재사용한다. 재발급은 401일 때만, 그것도 발급 후 1시간이 지난 토큰에 한한다. 429는 지연만 하고 토큰을 건드리지 않는다. HTTP 200 ≠ 성공 — 기대 블록 존재 여부 + `rsp_msg`로 판정하고 `rsp_cd` 단일 비교를 하지 않는다. `cts`/`cts_flag`는 응답 **헤더**에서 읽어 다음 요청 헤더로 되돌려 보낸다. REST는 초당 5회 수준.
- **호스트**: 운영 전용. REST `https://api.nhplug.com:8443`, WebSocket `wss://api.nhplug.com:7070/websocket`. 모의투자(acct_type `03`) 계좌는 목록에서 제외한다.
- **금액·수량 단위**: 금액은 KRW `Long`, 수량은 `Long`, 비중은 basis point `Int`(1250 = 12.50%). 부동소수 금액 금지.
- **커밋**: 각 태스크의 마지막 단계에서 커밋한다. 원격 푸시는 하지 않는다. 커밋 메시지 끝에 다음 두 줄을 붙인다.
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
  ```
- **검증**: 태스크를 완료로 표시하기 전에 그 태스크의 검증 명령을 실제로 실행하고 출력을 확인한다. 통과 주장 금지 — 출력이 근거다.

## 사양 대비 의도적 변경 (실행 전 확인 완료)

1. **버전**: 사양 §13의 버전은 작성 시점 추정치였고 §14.21이 "빌드 첫날 확정"으로 남겨 두었다. 2026-08-30 기준 실제 최신 안정판으로 확정한다 — AGP 9.3.2(사양은 9.1.1), Kotlin 2.4.10(2.3.x), Gradle 9.7.1, navigation 2.10.0, lifecycle 2.11.0, activity 1.13.0, datastore 1.2.1, Ktor 3.5.2, Koin 4.2.2, coroutines 1.11.0, serialization 1.11.0, ktlint-gradle 14.2.0.
2. **androidx.biometric 1.1.0** (사양은 1.4.x). 1.4.0은 alpha07까지만 있고 stable이 없다. 우리가 쓰는 API(`BiometricPrompt` + `CryptoObject` + `BIOMETRIC_STRONG` + `BiometricManager.canAuthenticate`)는 1.1.0에 전부 있다. `setUserAuthenticationParameters`는 Keystore API지 biometric 라이브러리가 아니다. 기기 테스트에서 문제가 나오면 1.4.0-alpha07로 올린다(§14 스모크).
3. **`PortfolioViewModel(acctNo: String, ...)`** — 사양 §3의 `SavedStateHandle` 대신 Koin `parametersOf`로 계좌번호를 주입한다. NavHost가 타입 세이프 Route를 백스택에 저장·복원하므로 프로세스 사망 후에도 같은 계좌번호가 들어온다(복원 동작 동일). 배선 코드가 줄고 Koin의 CreationExtras 의존이 사라진다.
4. **detekt 기본 룰 일부 비활성**: `MagicNumber`, `LongMethod`, `LongParameterList`, `TooManyFunctions`. Compose 화면과 상수 테이블에서 의미 없는 소음만 낸다. `MaxLineLength`는 140. 사양이 요구한 `ForbiddenImport` 1개는 그대로 유지한다.
5. **compileSdk 37 설치 필요**: 로컬 SDK에 platform 35/36/36.1만 있다(37 없음). Google 저장소에 `platforms;android-37.x`가 존재하므로 Task 1에서 설치한다.

## 파일 구조

각 파일의 책임. `di/`·`nav/`·`security/Prefs.kt`·`security/Crypto.kt`는 만들지 않는다(소비자가 1개씩이라 소비자 파일로 접는다).

| 파일 | 책임 | Task |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` | 빌드 설정·버전 카탈로그 | 1 |
| `app/proguard-rules.pro`, `config/detekt.yml`, `.editorconfig`, `.gitignore`, `.gitattributes`, `.github/workflows/ci.yml` | R8·정적분석·CI | 1 |
| `app/src/main/AndroidManifest.xml`, `res/values/{strings,themes}.xml`, `res/xml/data_extraction_rules.xml`, `res/mipmap-anydpi-v26/`, `res/drawable/` | 매니페스트·리소스·런처 아이콘 | 1 |
| `model/Model.kt` | `Account`·`Holding`·`Balance`·`Fill` — 시장 무관 순수 데이터 | 2 |
| `portfolio/Rebalance.kt` | 목표 비중 → 매수/매도 주식 수. 순수 함수 | 2 |
| `security/Vault.kt` | Preferences 키(`K`), 순수 암호 함수, Keystore HMAC, `Vault`(DEK·PIN·비밀 봉인) | 3, 4 |
| `api/NhApi.kt` | NH REST + WebSocket + JSON. private DTO. 앱에서 Ktor를 아는 유일한 파일 | 5, 6 |
| `ui/Format.kt` | 숫자·손익 색·예외 → 사용자 메시지 | 7 |
| `ui/Theme.kt` | organic M3 테마 | 7 |
| `security/Biometric.kt` | 인증 게이트 AES 키 + BiometricPrompt. 파일 1개 삭제로 기능 제거 | 8 |
| `lock/LockScreen.kt` | `PinMode`·`LockViewModel`·`PinFlow`·`PinPad` — 게이트와 설정이 공유 | 8 |
| `settings/SettingsScreen.kt` | 앱키 입력(write-only)·지문 토글·PIN 변경·초기화 | 9 |
| `accounts/AccountsScreen.kt` | 운영 계좌 목록 | 10 |
| `portfolio/PortfolioScreen.kt` | `PortfolioUi`·`PortfolioViewModel`·화면·목표 비중 다이얼로그 | 11 |
| `App.kt`, `MainActivity.kt` | Koin 모듈·DataStore·잠금 정책 / 게이트·Route·NavHost | 12 |
| `README.md` | 위협 모델·기기 스모크 체크리스트 | 13 |
| `app/src/test/kotlin/dev/nhportfolio/*Test.kt` | RebalanceTest·CryptoTest·VaultTest·NhApiTest·NhSocketTest·FormatTest | 2~7 |

---

## Task 1: 툴체인 및 빌드 골격

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `config/detekt.yml`, `.editorconfig`, `.gitignore`, `.gitattributes`, `.github/workflows/ci.yml`, `local.properties`

**Interfaces:**
- Consumes: 없음 (최초 태스크)
- Produces: 빌드 가능한 빈 `:app` 모듈. 이후 모든 태스크가 `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `./gradlew detekt ktlintCheck`를 사용한다. 버전 카탈로그 별칭: `libs.androidx.compose.bom`, `libs.ktor.client.core` 등 (아래 `libs.versions.toml` 참조).

- [ ] **Step 1: JDK와 Android SDK 위치 확인**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$JAVA_HOME/bin/java" -version          # openjdk 21.x 여야 한다
ls "$LOCALAPPDATA/Android/Sdk/platforms" # 현재: android-35 android-36 android-36.1 (37 없음)
```

기대: java 21 확인. platform 37이 없다는 것도 확인(Step 9에서 설치).

- [ ] **Step 2: Gradle 래퍼 만들기**

로컬에 `gradle` 실행 파일이 없으므로 래퍼 JAR를 기존 프로젝트에서 복사한다(래퍼 JAR는 배포판 버전과 무관하게 부트스트랩만 한다). `D:\wp_2026\fwa\gradle\wrapper\gradle-wrapper.jar`가 있으면 그것을 쓰고, 없으면 `curl -L -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar`로 받는다.

```bash
mkdir -p gradle/wrapper
cp "/d/wp_2026/fwa/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/gradle-wrapper.jar
cp "/d/wp_2026/fwa/gradlew" gradlew
cp "/d/wp_2026/fwa/gradlew.bat" gradlew.bat
chmod +x gradlew
```

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: 루트 빌드 파일과 버전 카탈로그**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nh-portfolio"
include(":app")
```

`build.gradle.kts` (루트):

```kotlin
// 모듈이 하나뿐이라 subprojects 블록을 두지 않는다 — 정적분석 플러그인도 :app 에서 직접 적용한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.3.2"
kotlin = "2.4.10"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
navigationCompose = "2.10.0"
lifecycle = "2.11.0"
datastore = "1.2.1"
biometric = "1.1.0"
ktor = "3.5.2"
koin = "4.2.2"
serialization = "1.11.0"
coroutines = "1.11.0"
detekt = "1.23.8"
ktlint = "14.2.0"
ktlintEngine = "1.8.0"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
androidx-biometric = { module = "androidx.biometric:biometric", version.ref = "biometric" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
koin-androidx-compose = { module = "io.insert-koin:koin-androidx-compose", version.ref = "koin" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }
```

- [ ] **Step 4: 앱 모듈 빌드 파일**

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    parallel = true
}

ktlint {
    version.set(libs.versions.ktlintEngine.get())
    android.set(true)
}

android {
    namespace = "dev.nhportfolio"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.nhportfolio"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }          // buildConfig 는 켜지 않는다 (BuildConfig 참조 0개)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main").kotlin.srcDir("src/main/kotlin")
        getByName("test").kotlin.srcDir("src/test/kotlin")
    }

    testOptions { unitTests.isReturnDefaultValues = true }   // android.util.Log 스텁
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
        optIn.addAll(
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.androidx.datastore.preferences.core)
}
```

`app/proguard-rules.pro`:

```proguard
# 릴리스에서 Log.d/v 제거 — 앱 전체에서 로그를 지우는 단일 메커니즘
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# kotlinx.serialization — @Serializable 클래스의 serializer 보존
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    static **$* INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.nhportfolio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation 타입 세이프 Route (@Serializable sealed interface)
-keep class dev.nhportfolio.Route { *; }
-keep class dev.nhportfolio.Route$* { *; }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
```

- [ ] **Step 5: 매니페스트와 리소스**

`app/src/main/AndroidManifest.xml` — 이 태스크에서는 `<activity>`가 없다(Task 12에서 추가). 앱은 빌드되지만 실행 아이콘은 없다.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />

    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="false"
        android:theme="@style/Theme.NhPortfolio" />
</manifest>
```

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NH 포트폴리오</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.NhPortfolio" parent="@android:style/Theme.DeviceDefault.DayNight.NoActionBar">
        <item name="android:windowBackground">@android:color/transparent</item>
    </style>
</resources>
```

`app/src/main/res/values/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#3F6B4A</color>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml` — 새싹 모양(organic) 단색 벡터:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path
        android:fillColor="#F6F1E7"
        android:pathData="M54,80 C54,62 54,52 54,44 M54,58 C44,58 36,50 36,40 C46,40 54,48 54,58 Z M54,52 C64,52 72,44 72,34 C62,34 54,42 54,52 Z" />
    <path
        android:fillColor="#F6F1E7"
        android:pathData="M50,78 h8 v6 h-8 z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (그리고 `ic_launcher_round.xml`도 동일 내용):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 6: 정적 분석 설정**

`config/detekt.yml`:

```yaml
build:
  maxIssues: 0

complexity:
  LongMethod:
    active: false            # Compose 화면은 길다 — ktlint 와 리뷰가 잡는다
  LongParameterList:
    active: false
  TooManyFunctions:
    active: false

exceptions:
  SwallowedException:
    active: false            # cause 를 일부러 버린다 — 평문·앱키 URL 이 로그/크래시에 실리면 안 된다
  TooGenericExceptionCaught:
    active: false            # loadResult 가 앱 전체의 유일한 catch(Exception) 지점이다
  InstanceOfCheckForException:
    active: false            # userMessage() 가 예외 타입으로 분기한다

style:
  MagicNumber:
    active: false            # bp·잠금 시간·바이트 길이 상수가 전부 걸린다
  ReturnCount:
    active: false            # 보안 코드의 early return 이 중첩 if 보다 읽기 쉽다
  MaxLineLength:
    maxLineLength: 140
  ForbiddenImport:
    active: true
    includes: ['**/model/**', '**/portfolio/Rebalance.kt']
    imports:
      - 'android.*'
      - 'androidx.*'
      - 'io.ktor.*'
      - 'org.koin.*'
      - 'kotlinx.serialization.*'

naming:
  FunctionNaming:
    ignoreAnnotated: ['Composable']
  TopLevelPropertyNaming:
    active: false            # Compose 색·스타일 값은 PascalCase, 상수 집합은 UPPER_SNAKE 가 관례다
```

> detekt 는 설정 파일의 키 위치를 검증한다. `Property 'X' is misplaced` 로 실패하면 리포트가 알려주는 섹션으로 그 항목을 옮긴다. 여기에 없는 규칙이 걸리면(예: `ThrowsCount`) 설정을 늘리지 말고 해당 함수에 `@Suppress("규칙명")` 을 붙인다 — 규칙을 전역으로 끄는 것보다 범위가 좁다.

`.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 4

[*.{kt,kts}]
max_line_length = 140
ktlint_code_style = ktlint_official
ktlint_standard_function-naming = disabled

[*.{xml,yml,yaml,toml}]
indent_size = 2
```

`.gitattributes`:

```
* text=auto eol=lf
*.bat text eol=crlf
*.jar binary
```

`.gitignore`:

```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
captures/
.externalNativeBuild/
.cxx/
.superpowers/
```

`local.properties` (커밋 안 됨 — `.gitignore`에 있음):

```properties
sdk.dir=C\:\\Users\\gmdjl\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 7: CI 워크플로**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build, test, analyse
        run: ./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug assembleRelease --no-daemon
      - name: Upload reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: app/build/reports
      - name: Upload R8 mapping
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: mapping
          path: app/build/outputs/mapping/release
```

- [ ] **Step 8: 첫 빌드 실행 (실패 예상 — platform 37 없음)**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug --no-daemon
```

기대: Gradle 9.7.1 배포판을 내려받은 뒤, `compileSdk 37`에 해당하는 platform 을 찾지 못해 실패하거나(라이선스가 수락되어 있으면 AGP가 자동으로 내려받아 성공할 수도 있다). 성공하면 Step 9를 건너뛴다.

- [ ] **Step 9: Android platform 37 설치 (Step 8이 실패한 경우에만)**

`$LOCALAPPDATA/Android/Sdk/cmdline-tools`가 없으므로 커맨드라인 도구를 먼저 설치한다.

```bash
SDK="$LOCALAPPDATA/Android/Sdk"
mkdir -p "$SDK/cmdline-tools"
curl -L -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip
unzip -q /tmp/cmdtools.zip -d "$SDK/cmdline-tools"
mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --list | grep 'platforms;android-37'
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" "platforms;android-37" "build-tools;37.0.0"
```

`--list` 출력에 `android-37.0`/`37.1`/`37.2`가 여러 개면 가장 높은 것을 설치한다. `commandlinetools-win-*_latest.zip`의 정확한 파일명이 404면 <https://developer.android.com/studio#command-line-tools-only>에서 현재 링크를 확인한다.

**대안(설치가 막히는 경우)**: `compileSdk`/`targetSdk`를 36으로 내리고 Compose BOM을 `2026.06.01`로 내린다. minSdk 31과 이 계획의 코드는 전부 API 31~36 범위이므로 그대로 동작한다. 이 경우 사양 §13의 버전 표를 함께 수정한다.

- [ ] **Step 10: 빌드·정적분석 통과 확인**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew ktlintFormat --no-daemon
./gradlew assembleDebug detekt ktlintCheck --no-daemon
```

기대: `BUILD SUCCESSFUL`. 실패하면 메시지대로 고친다(주로 ktlint 포맷 — `ktlintFormat`이 자동 수정).

- [ ] **Step 11: 커밋**

```bash
git add -A
git commit -m "$(cat <<'MSG'
build: Gradle/AGP/Compose 골격과 정적분석·CI 설정

단일 :app 모듈, compileSdk 37 / minSdk 31, 버전 카탈로그, R8 규칙,
detekt(ForbiddenImport)·ktlint, GitHub Actions CI, 백업/추출 제외 규칙.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 2: 도메인 모델과 리밸런스 계산

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/model/Model.kt`
- Create: `app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt`

**Interfaces:**
- Consumes: 없음 (순수 Kotlin, 어떤 라이브러리도 import 하지 않는다 — detekt `ForbiddenImport`가 강제)
- Produces:
  - `dev.nhportfolio.model.Account(no: String)`
  - `dev.nhportfolio.model.Holding(code, name, qty, remainQty, avgPrice, price, evalAmt, pnlRate)`
  - `dev.nhportfolio.model.Balance(cash: Long, holdings: List<Holding>)`
  - `dev.nhportfolio.model.Fill(acctNo, name, qty, price, time)`
  - `dev.nhportfolio.portfolio.Rebalance.CASH: String`, `Rebalance.Line`, `Rebalance.Plan`, `Rebalance.plan(balance, targetsBp): Plan`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt`:

```kotlin
package dev.nhportfolio

import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Holding
import dev.nhportfolio.portfolio.Rebalance
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun holding(code: String, qty: Long, price: Long, evalAmt: Long = qty * price) =
    Holding(code = code, name = code, qty = qty, remainQty = qty, avgPrice = price, price = price, evalAmt = evalAmt, pnlRate = 0.0)

class RebalanceTest {

    @Test
    fun `분모는 보유 평가금액 합계와 예수금이다`() {
        val b = Balance(cash = 100_000, holdings = listOf(holding("005930", 10, 70_000), holding("000660", 5, 20_000)))
        val plan = Rebalance.plan(b, emptyMap())
        assertEquals(100_000 + 700_000 + 100_000, plan.total)
    }

    @Test
    fun `자산 비중은 basis point 로 계산된다`() {
        val b = Balance(cash = 250_000, holdings = listOf(holding("A", 1, 750_000)))
        val plan = Rebalance.plan(b, emptyMap())
        assertEquals(7_500, plan.lines.first { it.code == "A" }.weightBp)
        assertEquals(2_500, plan.lines.last().weightBp)
    }

    @Test
    fun `목표 주식 수는 내림한다`() {
        // total = 1_000_000, 목표 50% = 500_000, 현재가 45_000 -> 11.11주 -> 11주
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 0, 45_000, evalAmt = 0)))
        val plan = Rebalance.plan(b, mapOf("A" to 5_000))
        assertEquals(11, plan.lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `목표가 없으면 델타는 null 이다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 3, 1_000)))
        assertNull(Rebalance.plan(b, emptyMap()).lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `현재가가 0 이면 델타는 null 이다`() {
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 3, 0, evalAmt = 0)))
        assertNull(Rebalance.plan(b, mapOf("A" to 5_000)).lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `매도는 음수 델타로 나온다`() {
        // total = 1_000_000, 목표 10% = 100_000, 현재가 10_000 -> 10주 보유 100주 -> -90
        val b = Balance(cash = 0, holdings = listOf(holding("A", 100, 10_000)))
        assertEquals(-90, Rebalance.plan(b, mapOf("A" to 1_000)).lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `현금 행은 마지막이고 목표를 가질 수 있다`() {
        val b = Balance(cash = 500_000, holdings = listOf(holding("A", 1, 500_000)))
        val plan = Rebalance.plan(b, mapOf(Rebalance.CASH to 3_000))
        val last = plan.lines.last()
        assertEquals(Rebalance.CASH, last.code)
        assertEquals(3_000, last.targetBp)
        assertNull(last.deltaShares)
    }

    @Test
    fun `종목코드 CASH 인 보유는 현금 행이 아니다`() {
        val b = Balance(cash = 100, holdings = listOf(holding("CASH", 1, 900)))
        val plan = Rebalance.plan(b, mapOf("CASH" to 1_000))
        assertEquals(2, plan.lines.size)
        assertEquals("CASH", plan.lines[0].code)
        assertEquals(Rebalance.CASH, plan.lines[1].code)
        assertEquals(1_000, plan.lines[0].targetBp)
        assertNull(plan.lines[1].targetBp)
    }

    @Test
    fun `목표 합계와 매매 후 예수금을 계산한다`() {
        // total = 1_000_000. A 목표 60% -> 600_000 / 10_000 = 60주, 현재 0주 -> +60 -> 600_000 지출
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 0, 10_000, evalAmt = 0)))
        val plan = Rebalance.plan(b, mapOf("A" to 6_000, Rebalance.CASH to 4_000))
        assertEquals(10_000, plan.targetSumBp)
        assertEquals(400_000, plan.cashAfter)
    }

    @Test
    fun `예수금이 모자라면 매매 후 예수금이 음수다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 1, 1_000), holding("B", 1, 1_000)))
        // total = 2_000. A 목표 100% -> 2주, 현재 1주 -> +1 -> 1_000 지출, 예수금 0
        val plan = Rebalance.plan(b, mapOf("A" to 10_000))
        assertEquals(-1_000, plan.cashAfter)
    }

    @Test
    fun `빈 포트폴리오는 예외 없이 현금 100 퍼센트다`() {
        val plan = Rebalance.plan(Balance(cash = 10_000, holdings = emptyList()), emptyMap())
        assertEquals(1, plan.lines.size)
        assertEquals(10_000, plan.lines.single().weightBp)
    }

    @Test
    fun `모두 0 이면 비중도 0 이다`() {
        val plan = Rebalance.plan(Balance(cash = 0, holdings = emptyList()), emptyMap())
        assertEquals(0, plan.total)
        assertEquals(0, plan.lines.single().weightBp)
    }

    @Test
    fun `범위를 벗어난 목표 비중은 거부한다`() {
        val b = Balance(cash = 1_000, holdings = emptyList())
        assertFailsWith<IllegalArgumentException> { Rebalance.plan(b, mapOf("A" to -1)) }
        assertFailsWith<IllegalArgumentException> { Rebalance.plan(b, mapOf("A" to 10_001)) }
    }

    @Test
    fun `목표 합계가 100 퍼센트 이하면 매수 금액도 총자산 이하다`() {
        val rnd = Random(42)
        repeat(200) {
            val holdings = List(rnd.nextInt(1, 6)) { i ->
                holding("C$i", rnd.nextLong(0, 100), rnd.nextLong(1, 100_000))
            }
            val cash = rnd.nextLong(0, 10_000_000)
            var left = 10_000
            val targets = holdings.associate { h ->
                val bp = rnd.nextInt(0, left + 1).also { left -= it }
                h.code to bp
            }
            val plan = Rebalance.plan(Balance(cash, holdings), targets)
            val bought = holdings.sumOf { h ->
                val d = plan.lines.first { it.code == h.code }.deltaShares ?: 0L
                (h.qty + d) * h.price
            }
            // 지연 오버로드 assertTrue(msg) { block } 과 시그니처가 겹치므로 즉시 오버로드를 쓴다
            assertTrue(bought <= plan.total, "bought=$bought total=${plan.total}")
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testDebugUnitTest --no-daemon
```

기대: 컴파일 실패 — `Unresolved reference: model`, `Unresolved reference: Rebalance`.

- [ ] **Step 3: 모델 작성**

`app/src/main/kotlin/dev/nhportfolio/model/Model.kt`:

```kotlin
package dev.nhportfolio.model

/**
 * NH 계좌. 운영(acct_type 01·02) 계좌만 여기까지 도달한다.
 * [no] 는 acctinfo 의 acct_no 이며 그대로 잔고 API 의 act_no 로 쓴다.
 */
data class Account(val no: String)

/**
 * 보유 종목 한 줄. 시장 무관 — 금액은 KRW, 수량은 정수.
 *
 * @param code 종목코드 (iem_cd)
 * @param name 종목명 (iem_nm)
 * @param qty 보유수량 (itg_bnc_qty)
 * @param remainQty 잔고수량 (rsdl_qty)
 * @param avgPrice 평균매입가 (phs_pr)
 * @param price 현재가 (now_pr)
 * @param evalAmt 평가금액 (eal_amt)
 * @param pnlRate 수익률 (pft_rt)
 */
data class Holding(
    val code: String,
    val name: String,
    val qty: Long,
    val remainQty: Long,
    val avgPrice: Long,
    val price: Long,
    val evalAmt: Long,
    val pnlRate: Double,
)

/** [cash] 는 D+2 예수금 — 당일 체결이 즉시 반영된다. */
data class Balance(val cash: Long, val holdings: List<Holding>)

/** 실시간 체결통보 한 건. [time] 은 HHmmss. */
data class Fill(
    val acctNo: String,
    val name: String,
    val qty: Long,
    val price: Long,
    val time: String,
)
```

- [ ] **Step 4: 리밸런스 계산 작성**

`app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt`:

```kotlin
package dev.nhportfolio.portfolio

import dev.nhportfolio.model.Balance

/**
 * 목표 비중 -> 매수/매도 주식 수. 순수 함수 — 네트워크도 상태도 없다.
 *
 * 비중 단위는 basis point (1250 = 12.50%). 분모는 `예수금 + Σ 평가금액`.
 */
object Rebalance {

    /** 현금 행의 코드. 종목코드가 될 수 없는 값이어야 한다 (NASDAQ 에 "CASH" 티커가 실재한다). */
    const val CASH = "\$CASH"

    private const val FULL_BP = 10_000

    /** [deltaShares] 가 null 이면 목표가 없거나 현재가가 0 이하라 계산할 수 없다는 뜻이다. */
    data class Line(
        val code: String,
        val currentAmt: Long,
        val weightBp: Int,
        val targetBp: Int?,
        val deltaShares: Long?,
    )

    /** [lines] 의 마지막 원소는 항상 현금 행이다. */
    data class Plan(
        val lines: List<Line>,
        val total: Long,
        val cashAfter: Long,
        val targetSumBp: Int,
    )

    fun plan(balance: Balance, targetsBp: Map<String, Int>): Plan {
        require(targetsBp.values.all { it in 0..FULL_BP }) { "목표 비중은 0~100% 범위여야 합니다" }

        val total = balance.cash + balance.holdings.sumOf { it.evalAmt }
        var spend = 0L
        val holdingLines = balance.holdings.map { h ->
            val targetBp = targetsBp[h.code]
            val delta = if (targetBp == null || h.price <= 0) {
                null
            } else {
                total * targetBp / FULL_BP / h.price - h.qty
            }
            if (delta != null) spend += delta * h.price
            Line(h.code, h.evalAmt, weightBp(h.evalAmt, total), targetBp, delta)
        }
        val lines = holdingLines + Line(CASH, balance.cash, weightBp(balance.cash, total), targetsBp[CASH], null)
        return Plan(
            lines = lines,
            total = total,
            cashAfter = balance.cash - spend,
            targetSumBp = lines.sumOf { it.targetBp ?: 0 },
        )
    }

    private fun weightBp(amount: Long, total: Long): Int =
        if (total <= 0) 0 else (amount * FULL_BP / total).toInt()
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon
```

기대: 14개 테스트 전부 PASS, detekt·ktlint 통과. `ForbiddenImport`가 `model/`·`Rebalance.kt`에서 android/androidx/ktor/koin/serialization import를 막는지 확인하려면 `Rebalance.kt`에 `import android.util.Log`를 잠시 넣고 `./gradlew detekt`가 실패하는지 본 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/model app/src/main/kotlin/dev/nhportfolio/portfolio app/src/test
git commit -m "$(cat <<'MSG'
feat: 도메인 모델과 리밸런스 계산

Account/Holding/Balance/Fill 과 Rebalance.plan.
분모 = 예수금 + Σ평가금액, 목표 주식수는 내림, 현금 행은 마지막.
라이브러리 import 0개 — detekt ForbiddenImport 로 강제.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 3: 암호 원시 함수

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/security/Vault.kt` (이 태스크에서는 키 정의 + 순수 함수까지만)
- Test: `app/src/test/kotlin/dev/nhportfolio/CryptoTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces (전부 `dev.nhportfolio.security` 패키지, 파일 내 top-level):
  - `object K` — DataStore Preferences 키 모음: `SECRETS`, `DEK_PIN`, `DEK_BIO`, `SALT`, `PBKDF2_ITERS`, `FAILS`, `LOCK_ELAPSED`, `LOCK_BOOT`
  - `const val PBKDF2_ITERS: Int` (= 10_000)
  - `fun seal(key: ByteArray, plain: ByteArray): ByteArray` — `iv(12) || ciphertext+tag`
  - `fun open(key: ByteArray, blob: ByteArray): ByteArray`
  - `fun pbkdf2(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray` (32바이트)
  - `fun lockoutMillis(fails: Int): Long`
  - `fun weakPin(pin: CharArray): Boolean`
  - `internal fun ByteArray.b64(): String`, `internal fun String.unb64(): ByteArray`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/CryptoTest.kt`:

```kotlin
package dev.nhportfolio

import dev.nhportfolio.security.lockoutMillis
import dev.nhportfolio.security.open
import dev.nhportfolio.security.pbkdf2
import dev.nhportfolio.security.seal
import dev.nhportfolio.security.weakPin
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun bytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

class CryptoTest {

    @Test
    fun `봉인한 것을 다시 연다`() {
        val key = bytes(32)
        val plain = "삼성전자 005930".toByteArray()
        assertContentEquals(plain, open(key, seal(key, plain)))
    }

    @Test
    fun `같은 평문을 두 번 봉인하면 결과가 다르다`() {
        val key = bytes(32)
        assertFalse(seal(key, "x".toByteArray()).contentEquals(seal(key, "x".toByteArray())))
    }

    @Test
    fun `변조된 blob 은 열리지 않는다`() {
        val key = bytes(32)
        val blob = seal(key, "secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertFailsWith<AEADBadTagException> { open(key, blob) }
    }

    @Test
    fun `다른 키로는 열리지 않는다`() {
        val blob = seal(bytes(32), "secret".toByteArray())
        assertFailsWith<AEADBadTagException> { open(bytes(32), blob) }
    }

    @Test
    fun `pbkdf2 는 결정적이고 솔트와 반복수에 반응한다`() {
        val salt = bytes(16)
        val a = pbkdf2("123456".toCharArray(), salt, 1_000)
        assertContentEquals(a, pbkdf2("123456".toCharArray(), salt, 1_000))
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(pbkdf2("123456".toCharArray(), bytes(16), 1_000)))
        assertFalse(a.contentEquals(pbkdf2("123456".toCharArray(), salt, 2_000)))
        assertFalse(a.contentEquals(pbkdf2("654321".toCharArray(), salt, 1_000)))
    }

    @Test
    fun `잠금 시간은 5회부터 두 배씩 늘고 1시간에서 멈춘다`() {
        val table = listOf(
            0 to 0L, 4 to 0L,
            5 to 30_000L, 6 to 60_000L, 7 to 120_000L, 8 to 240_000L,
            9 to 480_000L, 10 to 960_000L, 11 to 1_920_000L,
            12 to 3_600_000L, 20 to 3_600_000L,
            54 to 3_600_000L, 69 to 3_600_000L, 1_000 to 3_600_000L,
        )
        for ((fails, expected) in table) assertEquals(expected, lockoutMillis(fails), "fails=$fails")
    }

    @Test
    fun `단순한 PIN 은 거부하고 나머지는 허용한다`() {
        for (weak in listOf("000000", "111111", "123456", "654321", "345678")) {
            assertTrue(weakPin(weak.toCharArray()), weak)
        }
        for (ok in listOf("135790", "112233", "192837", "100000")) {
            assertFalse(weakPin(ok.toCharArray()), ok)
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*CryptoTest*'
```

기대: 컴파일 실패 — `Unresolved reference: security`.

- [ ] **Step 3: 최소 구현 작성**

`app/src/main/kotlin/dev/nhportfolio/security/Vault.kt` — 이 태스크에서 만드는 부분:

```kotlin
package dev.nhportfolio.security

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** DataStore Preferences 키. 바이트 blob 은 Base64 문자열로 저장한다. */
object K {
    /** DEK 로 봉인된 [Secrets] JSON */
    val SECRETS = stringPreferencesKey("secrets")

    /** PIN 유래 KEK 로 봉인된 DEK */
    val DEK_PIN = stringPreferencesKey("dek_pin")

    /** 생체 인증 Keystore 키로 암호화된 DEK */
    val DEK_BIO = stringPreferencesKey("dek_bio")

    val SALT = stringPreferencesKey("salt")
    val PBKDF2_ITERS = intPreferencesKey("pbkdf2_iters")
    val FAILS = intPreferencesKey("fails")

    /** 잠금 해제가 가능해지는 elapsedRealtime 시각 */
    val LOCK_ELAPSED = longPreferencesKey("lock_elapsed")

    /** LOCK_ELAPSED 를 기록할 때의 BOOT_COUNT — 재부팅을 감지해 잠금을 다시 무장한다 */
    val LOCK_BOOT = intPreferencesKey("lock_boot")
}

/**
 * PIN 스트레칭 반복수. 보안 상한이 아니다 — salt 와 반복수가 파일에 있으므로
 * 10^6 PIN 공간은 오프디바이스로 미리 계산할 수 있다. 진짜 상한은 하드웨어를
 * 떠나지 못하는 Keystore HMAC 키다. 여기서는 형식적 스트레칭만 한다.
 * 값은 setPin 시점에 [K.PBKDF2_ITERS] 로 저장되고 해제할 때는 저장값을 쓴다.
 */
const val PBKDF2_ITERS: Int = 10_000

private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12
private const val KEY_BITS = 256
private const val MAX_FREE_TRIES = 5
private const val LOCKOUT_BASE_MS = 30_000L
private const val LOCKOUT_MAX_MS = 3_600_000L
private const val LOCKOUT_MAX_SHIFT = 7

/** AES-256-GCM 봉인. 결과는 `iv(12) || ciphertext+tag`. */
fun seal(key: ByteArray, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.iv + cipher.doFinal(plain)
}

/**
 * [seal] 의 역. 키가 틀리거나 변조되면 [javax.crypto.AEADBadTagException],
 * blob 이 잘렸으면 다른 [java.security.GeneralSecurityException] 이 난다.
 */
fun open(key: ByteArray, blob: ByteArray): ByteArray {
    // iv(12) + 태그(16) 보다 짧으면 seal() 이 만든 blob 이 아니다. 이 검사가 없으면 JDK 가
    // AEADBadTagException 을 던져 '손상' 이 '틀린 PIN' 으로 오분류된다(§7 의 구분이 무너진다).
    if (blob.size < IV_BYTES + GCM_TAG_BITS / Byte.SIZE_BITS) throw GeneralSecurityException("blob too short")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES))
    return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
}

/** PIN -> 32바이트. 호출자가 [pin] 을 지운다. */
fun pbkdf2(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/**
 * 실패 [fails] 회일 때 잠금 시간(ms). 5회부터 30초에서 두 배씩, 상한 1시간.
 *
 * `shl` 은 Long 에서 하위 6비트만 쓰므로 시프트 폭을 반드시 포화시켜야 한다
 * (그러지 않으면 54회부터 음수가 되어 잠금이 사라진다).
 */
fun lockoutMillis(fails: Int): Long =
    if (fails < MAX_FREE_TRIES) {
        0L
    } else {
        (LOCKOUT_BASE_MS shl minOf(fails - MAX_FREE_TRIES, LOCKOUT_MAX_SHIFT)).coerceAtMost(LOCKOUT_MAX_MS)
    }

/** 모든 자리가 같거나 1씩 오르내리는 PIN(000000·123456·654321 등)을 거부한다. */
fun weakPin(pin: CharArray): Boolean {
    val deltas = pin.map { it - '0' }.zipWithNext { a, b -> b - a }.toSet()
    return deltas.size == 1 && deltas.first() in -1..1
}

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

internal operator fun Preferences.contains(key: Preferences.Key<*>): Boolean = key in asMap().keys
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon
```

기대: CryptoTest 7개 PASS (RebalanceTest 14개도 그대로 PASS).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/security app/src/test/kotlin/dev/nhportfolio/CryptoTest.kt
git commit -m "$(cat <<'MSG'
feat(security): AES-GCM 봉인·PBKDF2·잠금 시간·약한 PIN 판정

Preferences 키 정의와 순수 암호 함수. lockoutMillis 는 Long.shl 의
하위 6비트 문제를 피하려고 시프트 폭을 포화시킨다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 4: Vault — DEK 래핑, PIN 잠금, 비밀 봉인

> **구현 후 반영된 수정 (commit `252dcb9`).** 아래 코드 블록은 최초 계획본이며, 리뷰에서 발견된 키 위생 결함 11건이 실제 코드에는 이미 반영되어 있다. 재실행한다면 코드가 아니라 이 목록을 따를 것:
> 1. **(Critical)** `pbkdf2(...)` 산출물을 지역 변수에 묶고 `finally` 로 소거 — KEK 의 직접 원상이라 `lock()` 이후에도 남으면 잠금이 무의미해진다.
> 2. `pin.fill('0')` 을 `setPin`·`unlockWithPin` **함수 전체를 감싸는** `finally` 로 이동(약한 PIN 거부·잠금 반환 등 조기 종료 경로에서 PIN 이 남던 문제).
> 3. `hmac(derived, isFirst)` — PIN 변경 시 Keystore 키를 재생성하지 않는다. 재생성이 "쓰기 실패 시 복구 불가 + '틀린 PIN' 으로 오표시" 창의 유일한 원인이었다. `create` 플래그를 기록하는 테스트 추가.
> 4. `secretsFlow` 는 `dek?.let { decodeWith(it, prefs) } ?: Secrets()` 로 방어적으로 읽는다(`lock()` 과 `flatMapLatest` 취소 사이 창). `secrets()` 는 그대로 던진다.
> 5. `open()` KDoc 을 28바이트 경계로 정정 — 그 이상 길이의 변조는 AEAD 특성상 '틀린 키' 와 구분 불가.
> 6. `unlockWithPin` 이 `salt`/`wrapped` 의 base64 디코드를 방어적으로 감싸 `VaultCorruptException` 으로 라우팅.
> 7. `VaultTest` 의 `assertFailsWith<IllegalStateException>` 3곳에 `assertFalse(e is VaultCorruptException)` 추가(잠김과 손상을 구분하지 못하던 문제).
> 8. `CryptoTest` 가 28바이트 경계(`seal(key, ByteArray(0))`)를 고정.
> 9. `update()` 의 DEK 캡처/`lock()` 경합에 `ponytail:` 주석으로 상한 명시.
> 10. `opened` 는 무조건, `newDek` 는 **`isFirst` 일 때만** 소거 — PIN 변경 경로에서 `newDek` 는 살아있는 `dek` 와 같은 배열이라 무조건 소거하면 정상 세션을 파괴한다.
> 11. `hasPin` 에 `distinctUntilChanged()`.

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/security/Vault.kt` (Task 3 파일에 이어붙인다)
- Test: `app/src/test/kotlin/dev/nhportfolio/VaultTest.kt`

**Interfaces:**
- Consumes: Task 3의 `K`, `PBKDF2_ITERS`, `seal`, `open`, `pbkdf2`, `lockoutMillis`, `weakPin`, `b64()`, `unb64()`
- Produces:
  - `Secrets(appKey, appSecret, token, tokenIssuedAt, tokenExpiresAt)` — 전부 nullable/기본값, `toString()` 은 `"Secrets(***)"`
  - `sealed interface PinResult { Ok, Wrong(remaining: Int), LockedFor(millis: Long) }`
  - `class VaultCorruptException : IllegalStateException`
  - `class Vault(store, hmac, elapsed, bootCount)` — `unlocked: StateFlow<Boolean>`, `hasPin: Flow<Boolean>`, `secretsFlow: Flow<Secrets>`, `suspend secrets(): Secrets`, `suspend update((Secrets) -> Secrets)`, `suspend setPin(CharArray)`, `suspend unlockWithPin(CharArray): PinResult`, `fun lock()`, `suspend wipe()`, `internal fun dek(): ByteArray?`, `internal fun unlockWith(ByteArray)`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/VaultTest.kt`:

```kotlin
package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.security.K
import dev.nhportfolio.security.PBKDF2_ITERS
import dev.nhportfolio.security.PinResult
import dev.nhportfolio.security.Secrets
import dev.nhportfolio.security.Vault
import dev.nhportfolio.security.VaultCorruptException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GOOD_PIN = "135790"
private const val OTHER_PIN = "192837"

private class Fixture {
    var now: Long = 1_000L
    var boot: Int = 7
    var hmacThrows = false
    var hmacMissing = false

    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")

    val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) { File(dir, "vault.preferences_pb") }

    val vault = Vault(
        store = store,
        hmac = { data, _ ->
            when {
                hmacThrows -> error("keystore unavailable")
                hmacMissing -> null
                else -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data)
            }
        },
        elapsed = { now },
        bootCount = { boot },
    )

    suspend fun prefs() = store.data.first()
}

class VaultTest {

    @Test
    fun `PIN 을 설정하면 잠금이 풀리고 같은 PIN 으로 다시 열린다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        assertTrue(f.vault.unlocked.value)
        assertTrue(f.vault.hasPin.first())

        f.vault.update { it.copy(appKey = "KEY", appSecret = "SECRET") }
        f.vault.lock()
        assertFalse(f.vault.unlocked.value)

        assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
        assertEquals("KEY", f.vault.secrets().appKey)
    }

    @Test
    fun `약한 PIN 은 거부한다`() = runTest {
        val f = Fixture()
        assertFailsWith<IllegalArgumentException> { f.vault.setPin("123456".toCharArray()) }
    }

    @Test
    fun `틀린 PIN 은 남은 시도 횟수를 알려준다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        assertEquals(PinResult.Wrong(4), f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
        assertEquals(PinResult.Wrong(3), f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
        assertFalse(f.vault.unlocked.value)
    }

    @Test
    fun `5회 실패하면 잠기고 시간이 지나야 풀린다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        repeat(5) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) }

        val locked = f.vault.unlockWithPin(GOOD_PIN.toCharArray())
        assertTrue(locked is PinResult.LockedFor && locked.millis == 30_000L, "locked=$locked")

        f.now += 30_000
        assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
        assertNull(f.prefs()[K.FAILS])
        assertNull(f.prefs()[K.LOCK_ELAPSED])
    }

    @Test
    fun `재부팅해도 잠금이 다시 무장된다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        repeat(5) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) }

        f.boot = 8            // 재부팅
        f.now = 0             // elapsedRealtime 은 0 부터 다시 센다
        assertTrue(f.vault.unlockWithPin(GOOD_PIN.toCharArray()) is PinResult.LockedFor, "재부팅으로 잠금을 우회할 수 없어야 한다")

        f.now = 30_000
        assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
    }

    @Test
    fun `재부팅 직후 첫 시도가 가짜 잠금이 되지 않는다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        f.boot = 9
        f.now = 0
        assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
    }

    @Test
    fun `동시 시도가 실패 횟수를 잃어버리지 않는다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        listOf(
            async(Dispatchers.Default) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) },
            async(Dispatchers.Default) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) },
        ).awaitAll()
        assertEquals(2, f.prefs()[K.FAILS])
    }

    @Test
    fun `Keystore 가 죽어도 실패 횟수는 이미 기록되어 있다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        f.hmacThrows = true
        assertFailsWith<IllegalStateException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
        assertEquals(1, f.prefs()[K.FAILS])
    }

    @Test
    fun `Keystore 키 소실은 틀린 PIN 이 아니라 손상이다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        f.hmacMissing = true
        assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
    }

    @Test
    fun `잘린 blob 과 솔트 부재도 손상이다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.lock()
        f.store.edit { it[K.DEK_PIN] = "AAAAAAAAAAAAAAAA" }
        assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }

        f.store.edit { it.remove(K.SALT) }
        assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
    }

    @Test
    fun `변조된 비밀 blob 은 손상으로 보고한다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.update { it.copy(appKey = "KEY") }
        val blob = f.prefs()[K.SECRETS]!!
        f.store.edit { it[K.SECRETS] = blob.dropLast(4) + "AAAA" }
        assertFailsWith<VaultCorruptException> { f.vault.secrets() }
    }

    @Test
    fun `잠기면 비밀을 읽을 수 없고 흐름은 빈 값을 낸다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.update { it.copy(appKey = "KEY", token = "T") }
        f.vault.lock()
        assertFailsWith<IllegalStateException> { f.vault.secrets() }
        assertEquals(Secrets(), f.vault.secretsFlow.first())
    }

    @Test
    fun `초기화하면 잠기고 PIN 도 사라지며 옛 blob 은 열리지 않는다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.update { it.copy(appKey = "KEY") }
        val oldBlob = f.prefs()[K.SECRETS]!!

        f.vault.wipe()
        assertFalse(f.vault.unlocked.value)
        assertFalse(f.vault.hasPin.first())

        f.vault.setPin(GOOD_PIN.toCharArray())
        f.store.edit { it[K.SECRETS] = oldBlob }
        assertFailsWith<VaultCorruptException> { f.vault.secrets() }
    }

    @Test
    fun `입력한 PIN 배열은 지워진다`() = runTest {
        val f = Fixture()
        val pin = GOOD_PIN.toCharArray()
        f.vault.setPin(pin)
        assertTrue(pin.all { it == '0' }, "setPin 이 PIN 배열을 지워야 한다")

        f.vault.lock()
        val pin2 = GOOD_PIN.toCharArray()
        f.vault.unlockWithPin(pin2)
        assertTrue(pin2.all { it == '0' }, "unlockWithPin 이 PIN 배열을 지워야 한다")
    }

    @Test
    fun `PBKDF2 반복수는 저장되고 해제할 때 그 값을 쓴다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        assertEquals(PBKDF2_ITERS, f.prefs()[K.PBKDF2_ITERS])

        f.vault.lock()
        f.store.edit { it[K.PBKDF2_ITERS] = PBKDF2_ITERS + 1 }
        // 저장값이 달라지면 KEK 도 달라져 같은 PIN 이 틀린 것으로 판정된다 = 저장값을 쓴다는 증거
        assertTrue(f.vault.unlockWithPin(GOOD_PIN.toCharArray()) is PinResult.Wrong)
    }

    @Test
    fun `PIN 변경은 잠금이 풀린 상태에서만 되고 같은 비밀을 유지한다`() = runTest {
        val f = Fixture()
        f.vault.setPin(GOOD_PIN.toCharArray())
        f.vault.update { it.copy(appKey = "KEY") }

        f.vault.setPin(OTHER_PIN.toCharArray())
        f.vault.lock()
        assertEquals(PinResult.Ok, f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
        assertEquals("KEY", f.vault.secrets().appKey)

        f.vault.lock()
        assertFailsWith<IllegalStateException> { f.vault.setPin(GOOD_PIN.toCharArray()) }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*VaultTest*'
```

기대: 컴파일 실패 — `Unresolved reference: Vault`, `Secrets`, `PinResult`, `VaultCorruptException`.

- [ ] **Step 3: Vault 구현을 Vault.kt 에 이어붙인다**

Task 3에서 만든 `security/Vault.kt` 아래에 다음을 추가하고, 필요한 import 를 파일 상단에 합친다:
`android.os.SystemClock`, `android.security.keystore.KeyGenParameterSpec`, `android.security.keystore.KeyProperties`,
`androidx.datastore.core.DataStore`, `androidx.datastore.preferences.core.edit`,
`kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.flow.{Flow, MutableStateFlow, StateFlow, asStateFlow, first, flatMapLatest, flowOf, map}`,
`kotlinx.coroutines.sync.{Mutex, withLock}`, `kotlinx.coroutines.withContext`,
`kotlinx.serialization.{SerializationException, Serializable}`, `kotlinx.serialization.json.Json`,
`java.security.{GeneralSecurityException, KeyStore, ProviderException, SecureRandom}`,
`javax.crypto.{AEADBadTagException, KeyGenerator, Mac, SecretKey}`.

```kotlin
/**
 * 디스크에 봉인되어 저장되는 비밀 전부. 토큰은 메모리 홀더 없이 여기에만 산다 —
 * 잠그면 같은 UID 코드도 읽을 수 없다.
 */
@Serializable
data class Secrets(
    val appKey: String? = null,
    val appSecret: String? = null,
    val token: String? = null,
    val tokenIssuedAt: Long = 0,
    val tokenExpiresAt: Long = 0,
) {
    override fun toString(): String = "Secrets(***)"
}

sealed interface PinResult {
    data object Ok : PinResult

    data class Wrong(val remaining: Int) : PinResult

    data class LockedFor(val millis: Long) : PinResult
}

/** cause 를 붙이지 않는다 — 평문이나 JSON 조각이 스택트레이스에 실리지 않도록. */
class VaultCorruptException : IllegalStateException("secrets corrupt")

private const val PIN_MAC_ALIAS = "nh_pin_mac"
private const val DEK_BYTES = 32
private const val SALT_BYTES = 16

/**
 * 비밀 저장소. 잠금 상태 = 프로세스 메모리의 DEK 유무.
 *
 * 디스크에는 래핑본만 있다: `DEK_PIN = seal(HMAC(pbkdf2(pin, salt)), DEK)`,
 * `SECRETS = seal(DEK, Secrets JSON)`. HMAC 키는 AndroidKeyStore 를 떠나지 못하므로
 * DataStore 파일만 복사해서는 오프라인 대입이 불가능하다.
 *
 * [hmac] · [elapsed] · [bootCount] 는 JVM 테스트를 위한 생성자 훅이다 (인터페이스 없음).
 */
class Vault(
    private val store: DataStore<Preferences>,
    private val hmac: (data: ByteArray, create: Boolean) -> ByteArray? = ::keystoreHmac,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val bootCount: () -> Int,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pinMutex = Mutex()
    private val unlockedState = MutableStateFlow(false)

    @Volatile
    private var dek: ByteArray? = null

    val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    val hasPin: Flow<Boolean> = store.data.map { K.DEK_PIN in it }

    /** 잠기면 빈 [Secrets] 를 낸다 — 구독자(소켓)가 에러 없이 멈춘다. */
    val secretsFlow: Flow<Secrets> = unlockedState.flatMapLatest { isUnlocked ->
        if (isUnlocked) store.data.map { decode(it) } else flowOf(Secrets())
    }

    suspend fun secrets(): Secrets = decode(store.data.first())

    suspend fun update(transform: (Secrets) -> Secrets) {
        val key = dek ?: error("locked")
        store.edit { prefs ->
            val next = transform(decodeWith(key, prefs))
            prefs[K.SECRETS] = seal(key, json.encodeToString(next).toByteArray()).b64()
        }
    }

    /**
     * 최초 설정이면 새 DEK·솔트·Keystore 키를 만들고, 이후에는 잠금이 풀린 상태에서
     * 같은 DEK 를 새 PIN 으로 다시 래핑한다. [pin] 은 반환 전에 지워진다.
     */
    suspend fun setPin(pin: CharArray) {
        require(!weakPin(pin)) { "너무 단순한 PIN 입니다" }
        val prefs = store.data.first()
        val isFirst = K.DEK_PIN !in prefs
        val newDek = if (isFirst) randomBytes(DEK_BYTES) else (dek ?: error("locked"))
        val salt = if (isFirst) randomBytes(SALT_BYTES) else (prefs[K.SALT] ?: throw VaultCorruptException()).unb64()

        val kek = withContext(Dispatchers.Default) { hmac(pbkdf2(pin, salt, PBKDF2_ITERS), true) }
            ?: throw VaultCorruptException()
        try {
            store.edit {
                it[K.SALT] = salt.b64()
                it[K.PBKDF2_ITERS] = PBKDF2_ITERS
                it[K.DEK_PIN] = seal(kek, newDek).b64()
                it.remove(K.FAILS)
                it.remove(K.LOCK_ELAPSED)
                it.remove(K.LOCK_BOOT)
            }
        } finally {
            kek.fill(0)
            pin.fill('0')
        }
        dek = newDek
        unlockedState.value = true
    }

    /**
     * PIN 검증 = DEK_PIN 의 GCM 태그 검사. 해시를 저장하거나 비교하는 코드는 없다.
     * 실패 횟수를 **검증 전에** 기록한다 (쓰기가 실패하면 시도 자체가 중단된다).
     * 시계는 단조 시계 + BOOT_COUNT 뿐이라 시간 설정을 바꿔 잠금을 줄일 수 없다.
     */
    suspend fun unlockWithPin(pin: CharArray): PinResult = pinMutex.withLock {
        val initial = store.data.first()
        val prefs = if (bootCount() != initial[K.LOCK_BOOT]) {
            store.edit {
                it[K.LOCK_ELAPSED] = elapsed() + lockoutMillis(it[K.FAILS] ?: 0)
                it[K.LOCK_BOOT] = bootCount()
            }
        } else {
            initial
        }

        val lockedUntil = prefs[K.LOCK_ELAPSED] ?: 0L
        if (elapsed() < lockedUntil) return@withLock PinResult.LockedFor(lockedUntil - elapsed())

        val salt = prefs[K.SALT] ?: throw VaultCorruptException()
        val iterations = prefs[K.PBKDF2_ITERS] ?: throw VaultCorruptException()
        val wrapped = prefs[K.DEK_PIN] ?: throw VaultCorruptException()

        val fails = store.edit {
            val next = (it[K.FAILS] ?: 0) + 1
            it[K.FAILS] = next
            it[K.LOCK_ELAPSED] = elapsed() + lockoutMillis(next)
        }[K.FAILS] ?: 1

        val opened = withContext(Dispatchers.Default) {
            val kek = hmac(pbkdf2(pin, salt.unb64(), iterations), false) ?: throw VaultCorruptException()
            try {
                unwrap(kek, wrapped.unb64())
            } finally {
                kek.fill(0)
                pin.fill('0')
            }
        }
        if (opened == null) return@withLock PinResult.Wrong(maxOf(0, MAX_FREE_TRIES - fails))

        store.edit {
            it.remove(K.FAILS)
            it.remove(K.LOCK_ELAPSED)
            it.remove(K.LOCK_BOOT)
        }
        dek = opened
        unlockedState.value = true
        PinResult.Ok
    }

    fun lock() {
        val current = dek
        dek = null
        unlockedState.value = false
        current?.fill(0)
    }

    /**
     * 자격증명·토큰·목표 비중·PIN 을 전부 지운다. Keystore 키는 건드리지 않는다 —
     * 래핑본이 없는 키는 무용지물이고, [setPin] 과 지문 등록이 같은 별칭으로 덮어쓴다.
     */
    suspend fun wipe() {
        lock()
        store.edit { it.clear() }
    }

    internal fun dek(): ByteArray? = dek

    internal fun unlockWith(newDek: ByteArray) {
        dek = newDek
        unlockedState.value = true
    }

    private fun decode(prefs: Preferences): Secrets = decodeWith(dek ?: error("locked"), prefs)

    @Suppress("SwallowedException", "ThrowsCount")
    private fun decodeWith(key: ByteArray, prefs: Preferences): Secrets {
        val blob = prefs[K.SECRETS] ?: return Secrets()
        return try {
            json.decodeFromString(String(open(key, blob.unb64()), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw VaultCorruptException()
        } catch (e: SerializationException) {
            throw VaultCorruptException()
        } catch (e: IllegalArgumentException) {   // Base64 디코드 실패
            throw VaultCorruptException()
        }
    }

    @Suppress("SwallowedException")
    private fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray? =
        try {
            open(kek, wrapped)
        } catch (e: AEADBadTagException) {
            null                                  // 태그 불일치만 "틀린 PIN"
        } catch (e: GeneralSecurityException) {
            throw VaultCorruptException()         // 잘린 blob 등은 손상
        }
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

/**
 * AndroidKeyStore 의 HMAC 키로 서명한다. [create] 가 true 면 키를 새로 만들고(덮어쓰기),
 * false 인데 키가 없으면 null 을 돌려준다 — 키 소실이 "틀린 PIN" 으로 위장되지 않도록.
 */
private fun keystoreHmac(data: ByteArray, create: Boolean): ByteArray? {
    val key = if (create) {
        generatePinMacKey()
    } else {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(PIN_MAC_ALIAS, null) as? SecretKey ?: return null
    }
    return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
}

@Suppress("SwallowedException")
private fun generatePinMacKey(): SecretKey {
    fun spec(strongBox: Boolean) =
        KeyGenParameterSpec.Builder(PIN_MAC_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
    return try {
        generator.init(spec(true))
        generator.generateKey()
    } catch (e: ProviderException) {   // StrongBoxUnavailableException 은 ProviderException 의 하위 타입
        generator.init(spec(false))
        generator.generateKey()
    }
}
```

`MAX_FREE_TRIES` 는 Task 3에서 같은 파일에 `private const val` 로 선언했으므로 그대로 쓴다.

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon
```

기대: VaultTest 16개 PASS (CryptoTest·RebalanceTest 도 그대로 PASS).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/security app/src/test/kotlin/dev/nhportfolio/VaultTest.kt
git commit -m "$(cat <<'MSG'
feat(security): DEK 래핑 Vault - PIN 잠금, 비밀 봉인, 손상 판별

잠금 상태 = 메모리의 DEK 유무. 토큰은 봉인 blob 안에만 존재한다.
실패 횟수 선기록(fail closed), 단조 시계 + BOOT_COUNT 로 잠금 우회 차단,
Keystore 키 소실을 틀린 PIN 과 구분.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 5: NhApi — REST (토큰·계좌·잔고)

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt`

**Interfaces:**
- Consumes: Task 2의 `Account`·`Holding`·`Balance`, Task 4의 `Vault`·`Secrets`
- Produces:
  - `class NhException(val code: String, message: String) : Exception` — `code` 는 `rsp_cd` | `"HTTP<status>"` | `"AUTH"` | `"WS"`
  - `val NhJson: Json`
  - `data class NhResponse<A, B>(rspCd, rspMsg, output0, output1)` 과 `val NhResponse<*, *>.ok: Boolean`
  - `fun <T> NhResponse<*, *>.expect(block: T?, empty: T): T`
  - `inline fun <T> loadResult(block: () -> T): Result<T>` — 앱 전체의 유일한 예외 래핑 관용구
  - `class NhApi(vault: Vault, engine: HttpClientEngine = ...)` — `suspend fun accounts(): List<Account>`, `suspend fun balance(acct: Account): Balance`, `internal suspend fun token(rejected: String? = null): String`
  - (Task 6에서 `fun fills(): Flow<Fill>` 이 추가된다)

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt`:

```kotlin
package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.model.Account
import dev.nhportfolio.security.Vault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extra: Headers = Headers.Empty,
) = respond(
    content = body,
    status = status,
    headers = Headers.build {
        appendAll(extra)
        append(HttpHeaders.ContentType, "application/json")
    },
)

private const val TOKEN_BODY = """{"access_token":"T1","token_type":"Bearer","expires_in":86400}"""

private const val ACCOUNTS_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.","cust_no":"1",
 "Output_0":[{"acct_no":"20101036881","acct_type":"01"},{"acct_no":"50051036881","acct_type":"03"}]}
"""

private const val BALANCE_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.",
 "Output_0":{"dca":111,"nxt2_dd_dca":500000,"tot_eal_amt":700000},
 "Output_1":[{"iem_cd":"005930","iem_nm":"삼성전자","itg_bnc_qty":10.0,"rsdl_qty":10.0,
              "phs_pr":68000,"now_pr":70000,"eal_amt":700000,"pft_rt":2.94}]}
"""

private class Api {
    private val dir: File = Files.createTempDirectory("api").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")

    val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) { File(dir, "api.preferences_pb") }

    val vault = Vault(
        store = store,
        hmac = { data, _ -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data) },
        elapsed = { 0L },
        bootCount = { 1 },
    )

    val requests = mutableListOf<HttpRequestData>()
    var handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") }

    val api = NhApi(vault, MockEngine { request -> requests += request; handle(request) })

    val tokenCalls get() = requests.count { it.url.encodedPath == "/oauth2/token" }

    suspend fun ready() {
        vault.setPin("135790".toCharArray())
        vault.update { it.copy(appKey = "APPKEY", appSecret = "APPSECRET") }
    }

    suspend fun seedToken(token: String, expiresAt: Long, issuedAt: Long) {
        vault.update { it.copy(token = token, tokenExpiresAt = expiresAt, tokenIssuedAt = issuedAt) }
    }
}

class NhApiTest {

    @Test
    fun `콜드 스타트는 토큰을 정확히 한 번 발급한다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

        f.api.accounts()
        f.api.accounts()

        assertEquals(1, f.tokenCalls)
        val token = f.requests.first { it.url.encodedPath == "/oauth2/token" }
        assertEquals("POST", token.method.value)
        assertEquals("APPKEY", token.url.parameters["appkey"])
        assertEquals("APPSECRET", token.url.parameters["appsecretkey"])
        assertEquals("client_credentials", token.url.parameters["grant_type"])
        assertEquals("oob", token.url.parameters["scope"])
        assertNull(token.headers[HttpHeaders.Authorization])
        assertTrue(token.body.contentType.toString().startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `유효한 저장 토큰이 있으면 발급하지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
        f.handle = { json(ACCOUNTS_BODY) }

        f.api.accounts()
        assertEquals(0, f.tokenCalls)
    }

    @Test
    fun `만료된 토큰은 재발급하고 만료 시각을 저장한다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("T0", expiresAt = 1, issuedAt = 1)
        f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

        val before = System.currentTimeMillis()
        f.api.accounts()
        assertEquals(1, f.tokenCalls)

        val secrets = f.vault.secrets()
        assertEquals("T1", secrets.token)
        val expected = before + 86_400_000L - 60_000L
        assertTrue(secrets.tokenExpiresAt in expected..(expected + 5_000), "expiresAt=${secrets.tokenExpiresAt}")
    }

    @Test
    fun `운영 계좌만 남기고 모의계좌는 제외한다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

        assertEquals(listOf(Account("20101036881")), f.api.accounts())
    }

    @Test
    fun `계좌 목록은 모든 페이지를 합산한다`() = runTest {
        val f = Api()
        f.ready()
        var page = 0
        f.handle = { req ->
            when {
                req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                page++ == 0 -> json(
                    """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                    extra = headersOf("cts" to listOf("C1"), "cts_flag" to listOf("Y")),
                )
                else -> json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"2","acct_type":"02"}]}""")
            }
        }

        assertEquals(listOf(Account("1"), Account("2")), f.api.accounts())
        val second = f.requests.last()
        assertEquals("C1", second.headers["cts"])
        assertEquals("Y", second.headers["cts_flag"])
    }

    @Test
    fun `cts 가 반복되면 연속조회를 멈춘다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                json(TOKEN_BODY)
            } else {
                json(
                    """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                    extra = headersOf("cts" to listOf("SAME"), "cts_flag" to listOf("Y")),
                )
            }
        }

        f.api.accounts()
        assertEquals(2, f.requests.count { it.url.encodedPath == "/n2/acctinfo" })
    }

    @Test
    fun `잔고는 D+2 예수금과 보유 종목을 돌려준다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(BALANCE_BODY) }

        val balance = f.api.balance(Account("20101036881"))
        assertEquals(500_000, balance.cash)
        val h = balance.holdings.single()
        assertEquals("005930", h.code)
        assertEquals("삼성전자", h.name)
        assertEquals(10, h.qty)
        assertEquals(68_000, h.avgPrice)
        assertEquals(70_000, h.price)
        assertEquals(700_000, h.evalAmt)

        val body = f.requests.last { it.url.encodedPath.endsWith("/balance") }.body.toString()
        assertTrue("\"act_no\":\"20101036881\"" in body, body)
        assertTrue("\"qut_dit_cd\":\"UNT\"" in body, body)
        assertTrue("\"bnc_bse_cd\":\"1\"" in body, body)
    }

    @Test
    fun `보유 블록이 없어도 오류가 아니다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                json(TOKEN_BODY)
            } else {
                json("""{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.","Output_0":{"nxt2_dd_dca":1000}}""")
            }
        }

        val balance = f.api.balance(Account("1"))
        assertEquals(1_000, balance.cash)
        assertTrue(balance.holdings.isEmpty())
    }

    @Test
    fun `블록이 없어도 완료 메시지면 빈 결과다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req ->
            if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY)
            else json("""{"rsp_cd":"99999","rsp_msg":"정상처리 완료"}""")
        }

        assertTrue(f.api.accounts().isEmpty())
    }

    @Test
    fun `블록도 없고 완료도 아니면 업무 오류다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { req ->
            if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY)
            else json("""{"rsp_cd":"40010","rsp_msg":"종목코드 항목을 입력하세요."}""")
        }

        val e = assertFailsWith<NhException> { f.api.accounts() }
        assertEquals("40010", e.code)
        assertEquals("종목코드 항목을 입력하세요.", e.message)
    }

    @Test
    fun `두 번째 페이지의 오류도 부분 결과로 넘기지 않는다`() = runTest {
        val f = Api()
        f.ready()
        var page = 0
        f.handle = { req ->
            when {
                req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                page++ == 0 -> json(
                    """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                    extra = headersOf("cts" to listOf("C1"), "cts_flag" to listOf("Y")),
                )
                else -> json("""{"rsp_cd":"40010","rsp_msg":"조회 실패"}""")
            }
        }

        assertFailsWith<NhException> { f.api.accounts() }
    }

    @Test
    fun `401 이면 한 번 재발급하고 한 번 재시도한다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
        f.handle = { req ->
            when {
                req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                req.headers[HttpHeaders.Authorization] == "Bearer STALE" -> json("{}", HttpStatusCode.Unauthorized)
                else -> json(ACCOUNTS_BODY)
            }
        }

        assertEquals(1, f.api.accounts().size)
        assertEquals(1, f.tokenCalls)
        assertEquals("T1", f.vault.secrets().token)
    }

    @Test
    fun `동시에 401 을 만나도 발급은 한 번이다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
        f.handle = { req ->
            when {
                req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                req.headers[HttpHeaders.Authorization] == "Bearer STALE" -> json("{}", HttpStatusCode.Unauthorized)
                else -> json(ACCOUNTS_BODY)
            }
        }

        listOf(
            async(Dispatchers.Default) { f.api.accounts() },
            async(Dispatchers.Default) { f.api.accounts() },
        ).awaitAll()

        assertEquals(1, f.tokenCalls)
    }

    @Test
    fun `갓 발급한 토큰이 401 이면 다시 발급하지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
        f.handle = { req ->
            if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY)
            else json("{}", HttpStatusCode.Unauthorized)
        }

        assertEquals("HTTP401", assertFailsWith<NhException> { f.api.accounts() }.code)
        assertEquals("HTTP401", assertFailsWith<NhException> { f.api.accounts() }.code)
        assertEquals(1, f.tokenCalls, "1시간 창 안에서는 재발급하지 않는다")
    }

    @Test
    fun `429 는 지연만 하고 토큰을 건드리지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
        var attempts = 0
        f.handle = { if (attempts++ < 2) json("{}", HttpStatusCode.TooManyRequests) else json(ACCOUNTS_BODY) }

        assertEquals(1, f.api.accounts().size)
        assertEquals(0, f.tokenCalls)
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `토큰 요청이 네트워크 오류면 앱키가 메시지에 남지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { throw IOException("https://api.nhplug.com:8443/oauth2/token?appkey=APPKEY&appsecretkey=APPSECRET") }

        val e = assertFailsWith<IOException> { f.api.accounts() }
        assertFalse("appkey" in (e.message ?: ""), "메시지에 appkey 가 들어가면 안 된다")
        assertFalse("APPSECRET" in (e.message ?: ""))
        assertNull(e.cause)
        assertEquals(1, f.tokenCalls, "네트워크 오류를 재시도하지 않는다")
    }

    @Test
    fun `토큰 응답이 깨졌으면 본문을 노출하지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { json("""{"oops":"APPSECRET leaked"}""") }

        val e = assertFailsWith<NhException> { f.api.accounts() }
        assertEquals("AUTH", e.code)
        assertFalse("APPSECRET" in (e.message ?: ""))
    }

    @Test
    fun `토큰 엔드포인트가 4xx 면 HTTP 코드로 보고한다`() = runTest {
        val f = Api()
        f.ready()
        f.handle = { json("{}", HttpStatusCode.BadRequest) }

        assertEquals("HTTP400", assertFailsWith<NhException> { f.api.accounts() }.code)
    }

    @Test
    fun `잠긴 상태에서는 네트워크 요청 자체를 하지 않는다`() = runTest {
        val f = Api()
        f.ready()
        f.vault.lock()

        assertFailsWith<IllegalStateException> { f.api.accounts() }
        assertEquals(0, f.requests.size)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*NhApiTest*'
```

기대: 컴파일 실패 — `Unresolved reference: api`.

- [ ] **Step 3: NhApi 의 REST 부분 작성**

`app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt`:

```kotlin
package dev.nhportfolio.api

import android.util.Log
import dev.nhportfolio.model.Account
import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Holding
import dev.nhportfolio.security.Vault
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val REST = "https://api.nhplug.com:8443"
private const val TOKEN_URL = "$REST/oauth2/token"

/** acctinfo 의 acct_type: 01 운영 일반, 02 운영 주문대리인. 그 외(03 모의 등)는 목록에서 제외한다. */
private val LIVE_TYPES = setOf("01", "02")

/** 재발급 억제 창 — 발급한 지 이보다 짧은 토큰이 401 이면 만료가 아니라 자격/권한 문제다. */
private const val REISSUE_WINDOW_MS = 3_600_000L

private const val TOKEN_EARLY_EXPIRY_MS = 60_000L
private const val DEFAULT_TOKEN_TTL_SEC = 86_400L
private const val RATE_LIMIT_RETRIES = 3
private const val RATE_LIMIT_BASE_DELAY_MS = 300L
private const val WS_PING_SECONDS = 30L
private const val REQUEST_TIMEOUT_MS = 15_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

val NhJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** [code] 는 `rsp_cd` | `"HTTP<status>"` | `"AUTH"` | `"WS"`. 비밀은 절대 담지 않는다. */
class NhException(val code: String, message: String) : Exception(message)

/** NH 응답 봉투. 자산군과 무관하게 같은 모양이다. */
@Serializable
data class NhResponse<A, B>(
    @SerialName("rsp_cd") val rspCd: String = "",
    @SerialName("rsp_msg") val rspMsg: String = "",
    @SerialName("Output_0") val output0: A? = null,
    @SerialName("Output_1") val output1: B? = null,
)

private val OK_CODES = setOf("00000", "00166", "00221", "13578")

/** 정상 코드는 여러 개이고 API 마다 다르다 — 코드 집합 ∪ 메시지로 판정한다. */
val NhResponse<*, *>.ok: Boolean get() = rspCd in OK_CODES || "완료" in rspMsg

/**
 * 블록이 있으면 성공, 없으면 [ok] 일 때만 [empty], 아니면 업무 오류.
 * 조회 0건과 오류를 구분하는 유일한 지점이다.
 */
fun <T> NhResponse<*, *>.expect(block: T?, empty: T): T =
    block ?: if (ok) empty else throw NhException(rspCd, rspMsg)

/** 앱 전체에서 예외를 Result 로 바꾸는 유일한 관용구. 취소는 그대로 던진다. */
@Suppress("TooGenericExceptionCaught")
inline fun <T> loadResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * NH PLUG OpenAPI 클라이언트. HTTP·WebSocket·NH JSON 을 아는 앱의 유일한 파일이다.
 *
 * 토큰 규칙: 24시간 캐시, 401 일 때만 재발급(그것도 발급 1시간 경과 후),
 * 429 는 지연만, IO 오류는 재시도하지 않는다 — 재발급 경로를 하나로 유지해
 * NH 보안 알림을 유발하지 않기 위해서다.
 */
class NhApi(
    private val vault: Vault,
    engine: HttpClientEngine = OkHttp.create {
        config {
            pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
            retryOnConnectionFailure(false)   // OkHttp 자체 재전송도 금지 — 토큰 POST 이중 발급 차단
        }
    },
) {
    private val client = HttpClient(engine) {
        expectSuccess = false                 // HTTP 상태는 진실이 아니다 — 판정은 call/pages 가 한다
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    }
    private val tokenMutex = Mutex()

    suspend fun accounts(): List<Account> {
        val pages = pages<List<AccountDto>, JsonElement>(
            path = "/n2/acctinfo",
            input = buildJsonObject { put("Input_0", buildJsonObject { }) },
        )
        val first = pages.first()
        val all = first.expect(first.output0, emptyList()) + pages.drop(1).flatMap { it.output0.orEmpty() }
        return all.filter { it.type in LIVE_TYPES }.map { Account(it.no) }
    }

    suspend fun balance(acct: Account): Balance {
        val pages = pages<BalanceSummaryDto, List<HoldingDto>>(
            path = "/krstock/inquiry/v1/balance",
            input = buildJsonObject {
                put(
                    "Input_0",
                    buildJsonObject {
                        put("act_no", acct.no)
                        put("bnc_bse_cd", "1")        // 1.주식관련 총 평가(체결기준)
                        put("ltg_aot_dit_cd", "1")    // 1.상장종목
                        put("aet_bse", "1")           // 1.순자산
                        put("qut_dit_cd", "UNT")      // 통합시세
                    },
                )
            },
        )
        val first = pages.first()
        return Balance(
            cash = first.expect(first.output0, BalanceSummaryDto()).cash,
            holdings = pages.flatMap { it.output1.orEmpty() }.map { it.toHolding() },
        )
    }

    /**
     * 유효한 토큰이 있으면 그대로, 없으면 발급한다. [rejected] 는 방금 401 을 받은 토큰이다.
     * 뮤텍스 안에서 "내가 보낸 토큰 == 저장 토큰" 을 확인하므로 동시·순차 401 이 겹쳐도 발급은 한 번이다.
     */
    internal suspend fun token(rejected: String? = null): String = tokenMutex.withLock {
        val secrets = vault.secrets()
        val appKey = secrets.appKey ?: throw NhException("AUTH", "no appkey")
        val appSecret = secrets.appSecret ?: throw NhException("AUTH", "no appsecret")
        val current = secrets.token
        val now = System.currentTimeMillis()

        if (current != null && current != rejected && secrets.tokenExpiresAt > now) return@withLock current
        if (current != null && current == rejected && now - secrets.tokenIssuedAt < REISSUE_WINDOW_MS) {
            throw NhException("HTTP401", "token rejected")
        }

        withContext(NonCancellable) {           // 응답 수신과 저장 사이에 취소되면 발급 토큰을 잃는다
            val issued = issueToken(appKey, appSecret)
            val ttl = (issued.expiresIn.takeIf { it > 0 } ?: DEFAULT_TOKEN_TTL_SEC) * 1_000 - TOKEN_EARLY_EXPIRY_MS
            val at = System.currentTimeMillis()
            vault.update { it.copy(token = issued.accessToken, tokenIssuedAt = at, tokenExpiresAt = at + ttl) }
            issued.accessToken
        }
    }

    private suspend fun issueToken(appKey: String, appSecret: String): TokenDto {
        val response = loadResult {
            client.post(TOKEN_URL) {
                url {
                    parameters.append("appkey", appKey)
                    parameters.append("appsecretkey", appSecret)
                    parameters.append("grant_type", "client_credentials")
                    parameters.append("scope", "oob")
                }
                setBody(FormDataContent(Parameters.Empty))   // Content-Type 만 x-www-form-urlencoded, 본문은 비움
            }
        }.getOrElse { cause ->
            // cause 를 붙이지 않는다 — Ktor 타임아웃 메시지에는 appkey 가 담긴 URL 이 들어 있다
            throw IOException(cause::class.simpleName)
        }
        if (!response.status.isSuccess()) throw NhException("HTTP${response.status.value}", "token")
        return loadResult { NhJson.decodeFromString<TokenDto>(response.bodyAsText()) }
            .getOrElse { throw NhException("AUTH", "bad token body") }
    }

    private suspend fun call(path: String, body: JsonObject, cts: String?): HttpResponse {
        var bearer = token()
        var reissued = false
        var attempt = 0
        while (true) {
            val response = client.post(REST + path) {
                bearerAuth(bearer)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
                if (cts != null) {
                    headers.append("cts", cts)
                    headers.append("cts_flag", "Y")
                }
            }
            when {
                response.status == HttpStatusCode.Unauthorized && !reissued -> {
                    reissued = true
                    bearer = token(rejected = bearer)
                }
                response.status == HttpStatusCode.TooManyRequests && attempt < RATE_LIMIT_RETRIES ->
                    delay(RATE_LIMIT_BASE_DELAY_MS shl attempt++)
                else -> return response
            }
        }
    }

    /** 연속조회. `cts`/`cts_flag` 는 응답 **헤더**로 오고 다음 요청 헤더로 되돌려 보낸다. */
    private suspend inline fun <reified A, reified B> pages(path: String, input: JsonObject): List<NhResponse<A, B>> {
        val out = mutableListOf<NhResponse<A, B>>()
        var cts: String? = null
        while (true) {
            val response = call(path, input, cts)
            if (!response.status.isSuccess()) {
                throw NhException("HTTP${response.status.value}", response.status.description)
            }
            val parsed = NhJson.decodeFromString<NhResponse<A, B>>(response.bodyAsText())
            // 유일한 네트워크 로그. release 에서는 R8 이 제거한다.
            Log.d("NhApi", "$path rsp_cd=${parsed.rspCd} ${parsed.rspMsg}")
            if (parsed.output0 == null && parsed.output1 == null && !parsed.ok) {
                throw NhException(parsed.rspCd, parsed.rspMsg)
            }
            out += parsed
            val next = response.headers["cts"]
            if (response.headers["cts_flag"] != "Y" || next.isNullOrEmpty() || next == cts) return out
            cts = next
        }
    }
}

@Serializable
private data class AccountDto(
    @SerialName("acct_no") val no: String,
    @SerialName("acct_type") val type: String,
)

@Serializable
private data class BalanceSummaryDto(
    /** D+2 예수금 — 당일 체결이 즉시 반영된다 (dca 는 D+0 이라 이틀간 움직이지 않는다). */
    @SerialName("nxt2_dd_dca") val cash: Long = 0,
)

@Serializable
private data class HoldingDto(
    @SerialName("iem_cd") val code: String,
    @SerialName("iem_nm") val name: String = "",
    @SerialName("itg_bnc_qty") val qty: Double = 0.0,
    @SerialName("rsdl_qty") val remainQty: Double = 0.0,
    @SerialName("phs_pr") val avgPrice: Long = 0,
    @SerialName("now_pr") val price: Long = 0,
    @SerialName("eal_amt") val evalAmt: Long = 0,
    @SerialName("pft_rt") val pnlRate: Double = 0.0,
) {
    fun toHolding() = Holding(code, name, qty.toLong(), remainQty.toLong(), avgPrice, price, evalAmt, pnlRate)
}

@Serializable
private data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    override fun toString(): String = "TokenDto(***)"
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon
```

기대: NhApiTest 19개 PASS. 실패하면 흔한 원인 둘 — (a) `Log.d` 가 `isReturnDefaultValues` 없이 예외를 던진다(Task 1의 `testOptions` 확인), (b) `private suspend inline fun pages` 가 컴파일 오류를 내면 `internal` 로 바꾼다(같은 모듈이라 동작은 동일).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/api app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt
git commit -m "$(cat <<'MSG'
feat(api): NH PLUG REST 클라이언트 - 토큰, 계좌목록, 잔고

토큰 24시간 캐시, 401 에서만 재발급(발급 1시간 경과 조건), 429 는 지연만,
IO 오류 재시도 없음. 성공 판정은 블록 존재 + rsp_msg, cts 는 응답 헤더에서.
운영 계좌(01, 02)만 노출하고 예수금은 D+2 를 쓴다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 6: NhApi — WebSocket 체결통보

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/NhSocketTest.kt`

**Interfaces:**
- Consumes: Task 5의 `NhApi`·`NhJson`·`NhException`, Task 2의 `Fill`, Task 4의 `Vault.secretsFlow`
- Produces:
  - `fun NhApi.fills(): Flow<Fill>` — 앱이 쓰는 진입점
  - `internal fun NhApi.fillsFrom(ws: String): Flow<Fill>` — 테스트가 embedded 서버 URL 을 주입
  - `NhApi.CHANNELS: List<String>`, `NhApi.subscribeFrame(token, trCd): String`, `NhApi.parseFill(text): Fill?`, `NhApi.backoffMs(streak): Long` (전부 `internal companion object`)

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/NhSocketTest.kt` — 실제 소켓을 쓰므로 `runTest` 의 가상 시간 대신 `runBlocking` + 실시간을 쓴다.

```kotlin
package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.model.Fill
import dev.nhportfolio.security.Vault
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val D2 = """
{"header":{"tr_cd":"d2","tr_key":""},
 "body":{"userid":"ID","itemgb":"1","accountno":"20101036881","orderno":"30","issuecd":"005940",
         "slbygb":"2","concgty":"0000000005","concprc":"00000035550","conctime":"115606",
         "issue_nm":"NH투자증권"}}
"""

/** 구독 ack — d2 이지만 체결 필드가 없다. 재조회 트리거가 되면 안 된다. */
private const val ACK = """{"header":{"tr_cd":"d2"},"body":{"rsp_cd":"00000","rsp_msg":"정상"}}"""

private const val D3 = """{"header":{"tr_cd":"d3"},"body":{"accountno":"1","orderno":"2"}}"""

private const val BAD_QTY = """
{"header":{"tr_cd":"d2"},"body":{"accountno":"1","concgty":"","concprc":"100","conctime":"090000"}}
"""

private class Socket {
    private val dir: File = Files.createTempDirectory("ws").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256")

    val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) { File(dir, "ws.preferences_pb") }

    val vault = Vault(
        store = store,
        hmac = { data, _ -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data) },
        elapsed = { 0L },
        bootCount = { 1 },
    )
    val api = NhApi(vault)

    /** 클라이언트가 보낸 구독 프레임 원문. */
    val subscribes = CopyOnWriteArrayList<String>()

    /** 연결 횟수. */
    @Volatile var connections = 0

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** [onSubscribed] 는 구독 프레임을 받은 뒤 서버가 할 일. 반환하면 서버가 세션을 닫는다. */
    fun start(onSubscribed: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit): String {
        val s = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/websocket") {
                    connections++
                    subscribes += (incoming.receive() as Frame.Text).readText()
                    onSubscribed()
                }
            }
        }.start(wait = false)
        server = s
        val port = runBlocking { s.engine.resolvedConnectors().first().port }
        return "ws://127.0.0.1:$port/websocket"
    }

    suspend fun ready(token: String? = "TOKEN") {
        vault.setPin("135790".toCharArray())
        vault.update {
            it.copy(appKey = "K", appSecret = "S", token = token, tokenExpiresAt = Long.MAX_VALUE, tokenIssuedAt = 1)
        }
    }

    @AfterTest
    fun stop() {
        server?.stop(0, 0)
    }
}

private suspend fun await(timeoutMs: Long = 8_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) delay(25)
    assertTrue(condition(), "조건이 ${timeoutMs}ms 안에 충족되지 않았다")
}

private fun CoroutineScope.collectFills(api: NhApi, url: String, into: MutableList<Fill>): Job =
    launch(Dispatchers.Default) { api.fillsFrom(url).collect { into += it } }

class NhSocketTest {

    @Test
    fun `구독 프레임은 명세 그대로다`() = runBlocking {
        val s = Socket()
        val url = s.start { delay(3_000) }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(10_000) { await { s.subscribes.isNotEmpty() } }
        job.cancel()
        s.stop()

        val expected = Json.parseToJsonElement(
            """{"header":{"token":"TOKEN","tr_type":"1"},"body":{"tr_cd":"d2","tr_key":""}}""",
        )
        assertEquals(expected, Json.parseToJsonElement(s.subscribes.first()))
    }

    @Test
    fun `d2 체결 프레임을 Fill 로 바꾼다`() = runBlocking {
        val s = Socket()
        val url = s.start { send(Frame.Text(D2)); delay(3_000) }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(10_000) { await { fills.isNotEmpty() } }
        job.cancel()
        s.stop()

        val fill = fills.first()
        assertEquals("20101036881", fill.acctNo)
        assertEquals("NH투자증권", fill.name)
        assertEquals(5, fill.qty)
        assertEquals(35_550, fill.price)
        assertEquals("115606", fill.time)
    }

    @Test
    fun `ack 와 다른 채널과 깨진 수량은 무시하고 연결을 유지한다`() = runBlocking {
        val s = Socket()
        val url = s.start {
            send(Frame.Text(ACK))
            send(Frame.Text(D3))
            send(Frame.Text(BAD_QTY))
            send(Frame.Text("not json"))
            delay(300)
            send(Frame.Text(D2))
            delay(3_000)
        }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(10_000) { await { fills.isNotEmpty() } }
        job.cancel()
        s.stop()

        assertEquals(1, fills.size, "체결이 아닌 프레임은 Fill 이 되면 안 된다")
        assertEquals(1, s.connections, "무시한 프레임 때문에 재연결하면 안 된다")
    }

    @Test
    fun `서버가 정상 종료해도 다시 연결한다`() = runBlocking {
        val s = Socket()
        val url = s.start {
            send(Frame.Text(D2))
            close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(15_000) { await(12_000) { s.connections >= 2 } }
        job.cancel()
        s.stop()
    }

    @Test
    fun `토큰이 바뀌면 새 토큰으로 다시 구독한다`() = runBlocking {
        val s = Socket()
        val url = s.start { delay(10_000) }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(10_000) { await { s.subscribes.size == 1 } }
        s.vault.update { it.copy(token = "TOKEN2") }
        withTimeout(10_000) { await { s.subscribes.size == 2 } }
        job.cancel()
        s.stop()

        assertTrue("TOKEN2" in s.subscribes[1])
    }

    @Test
    fun `잠그면 세션이 끊기고 다시 열면 재구독한다`() = runBlocking {
        val s = Socket()
        val url = s.start { delay(10_000) }
        s.ready()
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        withTimeout(10_000) { await { s.subscribes.size == 1 } }
        s.vault.lock()
        delay(1_500)
        assertEquals(1, s.subscribes.size, "잠긴 동안 재연결하면 안 된다")

        s.vault.unlockWithPin("135790".toCharArray())
        withTimeout(10_000) { await { s.subscribes.size == 2 } }
        job.cancel()
        s.stop()
    }

    @Test
    fun `토큰이 없으면 아예 연결하지 않는다`() = runBlocking {
        val s = Socket()
        val url = s.start { delay(3_000) }
        s.ready(token = null)
        val fills = CopyOnWriteArrayList<Fill>()
        val job = collectFills(s.api, url, fills)

        delay(1_500)
        job.cancel()
        s.stop()

        assertEquals(0, s.connections)
    }

    @Test
    fun `백오프는 1초에서 두 배씩 늘고 30초에서 멈춘다`() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        expected.forEachIndexed { streak, ms -> assertEquals(ms, NhApi.backoffMs(streak), "streak=$streak") }
        assertEquals(30_000L, NhApi.backoffMs(1_000))
    }
}
```

> 재연결 **간격**은 실제 소켓 위에서 재기 어려워(가상 시간과 실 IO 를 섞으면 flaky) 검증하지 않는다. 대신 순수 함수 `backoffMs` 를 직접 검증하고, 재연결이 일어난다는 사실만 위 테스트로 확인한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*NhSocketTest*'
```

기대: 컴파일 실패 — `Unresolved reference: fillsFrom`, `backoffMs`.

- [ ] **Step 3: WebSocket 부분을 NhApi.kt 에 추가**

`NhApi` 클래스 안에 추가한다 (import 도 파일 상단에 합친다):
`dev.nhportfolio.model.Fill`, `io.ktor.client.plugins.websocket.webSocketSession`, `io.ktor.websocket.Frame`,
`io.ktor.websocket.readText`, `kotlinx.coroutines.cancel`, `kotlinx.coroutines.flow.{Flow, distinctUntilChanged, emptyFlow, flatMapLatest, flow, map, retryWhen}`,
`kotlinx.serialization.json.{decodeFromJsonElement, jsonObject, jsonPrimitive}`, `kotlin.random.Random`.

```kotlin
private const val WS = "wss://api.nhplug.com:7070/websocket"   // 통보 채널은 국내·해외 모두 7070
private const val BACKOFF_JITTER_MS = 500L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L
private const val BACKOFF_MAX_SHIFT = 5
```

```kotlin
    /**
     * 사용자 범위의 실시간 체결통보. **토큰의 함수**라서 토큰이 바뀌면 자동으로 재구독하고,
     * 잠기면([Vault.lock]) `secretsFlow` 가 빈 [Secrets] 를 내보내 세션이 구조적으로 취소된다 —
     * 타이밍 상수에 기대는 부분이 없다.
     *
     * 수집자가 하나면 세션도 하나다(NH 는 앱키당 2세션). 화면을 떠나면 취소가 소켓을 닫는다.
     */
    fun fills(): Flow<Fill> = fillsFrom(WS)

    internal fun fillsFrom(ws: String): Flow<Fill> {
        var streak = 0
        return vault.secretsFlow
            .map { it.token }
            .distinctUntilChanged()
            .flatMapLatest { cached ->
                if (cached == null) {
                    emptyFlow()
                } else {
                    flow {
                        val bearer = token()                        // 만료됐으면 여기서 발급된다
                        val session = client.webSocketSession(ws)
                        try {
                            CHANNELS.forEach { session.send(Frame.Text(subscribeFrame(bearer, it))) }
                            for (frame in session.incoming) {
                                streak = 0                          // 프레임을 받았으면 건강한 연결이다
                                if (frame is Frame.Text) parseFill(frame.readText())?.let { emit(it) }
                            }
                        } finally {
                            session.cancel()                        // close() 는 suspend 라 취소된 컨텍스트에서 못 쓴다
                        }
                        throw NhException("WS", "closed")           // 정상 Close 도 재연결 대상이다
                    }
                }
            }
            .retryWhen { _, _ ->
                delay(backoffMs(streak++) + Random.nextLong(BACKOFF_JITTER_MS))
                true
            }
    }

    internal companion object {
        /** 해외 체결통보 `d0` 를 붙일 때는 여기 한 줄. 통보 채널은 전부 같은 7070 세션이다. */
        internal val CHANNELS = listOf("d2")

        /**
         * 정확히
         * `{"header":{"token":"<token>","tr_type":"1"},"body":{"tr_cd":"<trCd>","tr_key":""}}`.
         * `tr_key` 는 빈 문자열로 **존재해야** 하고 `tr_type` 은 문자열 `"1"` 이다.
         */
        internal fun subscribeFrame(token: String, trCd: String): String =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("token", token)
                        put("tr_type", "1")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("tr_cd", trCd)
                        put("tr_key", "")
                    },
                )
            }.toString()

        /** 체결 프레임만 [Fill] 로. ack·다른 채널·깨진 값은 null 이고 연결은 유지된다. */
        internal fun parseFill(text: String): Fill? =
            runCatching {
                val root = NhJson.parseToJsonElement(text).jsonObject
                val trCd = root["header"]?.jsonObject?.get("tr_cd")?.jsonPrimitive?.content
                if (trCd == null || trCd !in CHANNELS) return null
                val body = root["body"] ?: return null
                NhJson.decodeFromJsonElement<FillDto>(body).toFill()
            }.getOrNull()

        internal fun backoffMs(streak: Int): Long =
            minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(streak, BACKOFF_MAX_SHIFT))
    }
```

파일 하단에 DTO 추가:

```kotlin
@Serializable
private data class FillDto(
    // 실제 체결 프레임이 반드시 갖는 세 필드에는 기본값을 두지 않는다 —
    // 그래야 ack·오류 프레임이 디코드에 실패해 null 이 된다.
    val accountno: String,
    val concgty: String,
    val concprc: String,
    @SerialName("issue_nm") val name: String = "",
    val conctime: String = "",
) {
    fun toFill(): Fill? = Fill(
        acctNo = accountno,
        name = name,
        qty = concgty.trim().toLongOrNull() ?: return null,     // "0000000005" -> 5
        price = concprc.trim().toLongOrNull() ?: return null,
        time = conctime,
    )
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon
```

기대: NhSocketTest 8개 PASS (전체 시간 20~40초 — 실제 소켓과 재연결 대기가 있다). 포트 충돌로 실패하면 `embeddedServer(CIO, port = 0)` 이 유지되는지 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/api app/src/test/kotlin/dev/nhportfolio/NhSocketTest.kt
git commit -m "$(cat <<'MSG'
feat(api): 실시간 체결통보 WebSocket

토큰의 함수로 만든 세션 - 토큰 교체는 재구독, 잠금은 구조적 취소.
정상 Close 도 재연결 대상이고 백오프는 1s~30s + 지터.
ack/다른 채널/깨진 값은 프레임 단위로 무시한다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 7: 표시 형식과 organic 테마

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/ui/Format.kt`
- Create: `app/src/main/kotlin/dev/nhportfolio/ui/Theme.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/FormatTest.kt`

**Interfaces:**
- Consumes: Task 5의 `NhException`, Task 4의 `VaultCorruptException`
- Produces (`dev.nhportfolio.ui`):
  - `fun Long.krw(): String`, `fun Long.shares(): String`, `fun Int.bpPct(): String`, `fun Double.pct(): String`
  - `@Composable fun plColor(value: Double): Color`
  - `fun Throwable.userMessage(): String`
  - `@Composable fun NhTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  - `internal val ProfitRed: Color`, `internal val LossBlue: Color`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/dev/nhportfolio/FormatTest.kt`:

```kotlin
package dev.nhportfolio

import dev.nhportfolio.api.NhException
import dev.nhportfolio.security.VaultCorruptException
import dev.nhportfolio.ui.bpPct
import dev.nhportfolio.ui.krw
import dev.nhportfolio.ui.pct
import dev.nhportfolio.ui.shares
import dev.nhportfolio.ui.userMessage
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {

    private val original = Locale.getDefault()

    @BeforeTest
    fun useForeignLocale() {
        Locale.setDefault(Locale.GERMANY)   // 기기 로케일이 달라도 결과가 같아야 한다
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `금액과 수량은 천 단위로 끊는다`() {
        assertEquals("1,234,567", 1_234_567L.krw())
        assertEquals("0", 0L.krw())
        assertEquals("-1,000", (-1_000L).krw())
        assertEquals("12,345", 12_345L.shares())
    }

    @Test
    fun `basis point 는 퍼센트로 보인다`() {
        assertEquals("12.50%", 1_250.bpPct())
        assertEquals("100.00%", 10_000.bpPct())
        assertEquals("0.00%", 0.bpPct())
    }

    @Test
    fun `수익률은 부호를 항상 붙인다`() {
        assertEquals("+2.94%", 2.94.pct())
        assertEquals("-3.10%", (-3.1).pct())
        assertEquals("+0.00%", 0.0.pct())
    }

    @Test
    fun `사용자 메시지는 비밀을 담지 않고 원인별로 다르다`() {
        assertTrue("앱 키" in NhException("AUTH", "no appkey").userMessage())
        assertTrue("앱 키" in NhException("HTTP401", "token rejected").userMessage())
        assertTrue("요청이 많" in NhException("HTTP429", "x").userMessage())
        assertEquals("종목코드 항목을 입력하세요.", NhException("40010", "종목코드 항목을 입력하세요.").userMessage())
        assertEquals("네트워크 오류", IOException("https://...?appkey=SECRET").userMessage())
        assertEquals("응답 형식 오류", SerializationException("field APPSECRET missing").userMessage())
        assertTrue("손상" in VaultCorruptException().userMessage())
        assertEquals("", IllegalStateException("locked").userMessage())
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
./gradlew testDebugUnitTest --no-daemon --tests '*FormatTest*'
```

기대: 컴파일 실패 — `Unresolved reference: ui`.

- [ ] **Step 3: Format 작성**

`app/src/main/kotlin/dev/nhportfolio/ui/Format.kt`:

```kotlin
package dev.nhportfolio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.nhportfolio.api.NhException
import dev.nhportfolio.security.VaultCorruptException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// 기기 로케일과 무관하게 한국 표기로 고정한다. DecimalFormat 은 스레드 안전하지 않으므로 매번 만든다
// (한 화면에 수십 개라 비용은 무시할 수준이다).
private fun formatter(pattern: String) = DecimalFormat(pattern, DecimalFormatSymbols(Locale.KOREA))

fun Long.krw(): String = formatter("#,##0").format(this)

fun Long.shares(): String = formatter("#,##0").format(this)

/** basis point -> 퍼센트 문자열. 1250 -> "12.50%" */
fun Int.bpPct(): String = formatter("#,##0.00").format(this / 100.0) + "%"

/** 수익률. 부호를 항상 붙인다. */
fun Double.pct(): String = formatter("+#,##0.00;-#,##0.00").format(this) + "%"

/** 국내 관례: 이익 빨강, 손실 파랑. */
@Composable
fun plColor(value: Double): Color = when {
    value > 0 -> ProfitRed
    value < 0 -> LossBlue
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 예외 -> 사용자 문구. 비밀이 담길 수 있는 원본 메시지는 절대 그대로 쓰지 않는다
 * (업무 오류 `rsp_msg` 만 예외 — NH 가 준 사용자용 문구다).
 *
 * [VaultCorruptException] 은 [IllegalStateException] 의 하위 타입이라 반드시 먼저 검사한다.
 */
fun Throwable.userMessage(): String = when (this) {
    is NhException -> when (code) {
        "AUTH", "HTTP400", "HTTP401" -> "인증 실패 — 설정에서 앱 키를 확인하세요"
        "HTTP429" -> "요청이 많습니다. 잠시 후 다시 시도하세요"
        "WS" -> "실시간 연결이 끊겼습니다"
        else -> message ?: "오류가 발생했습니다"
    }
    is VaultCorruptException -> "저장된 데이터가 손상되었습니다"
    is IOException -> "네트워크 오류"
    is SerializationException -> "응답 형식 오류"
    is IllegalStateException -> ""   // 잠김 — 게이트가 처리하므로 화면에 아무것도 띄우지 않는다
    else -> message ?: "오류가 발생했습니다"
}
```

- [ ] **Step 4: 테마 작성**

`app/src/main/kotlin/dev/nhportfolio/ui/Theme.kt`:

```kotlin
package dev.nhportfolio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// organic 팔레트 — 자연 톤. dynamic color 는 쓰지 않는다(계좌 화면이 기기마다 달라지면 안 된다).
private val Moss = Color(0xFF3F6B4A)
private val Sage = Color(0xFFDDE8D6)
private val Fern = Color(0xFFA8C8A0)
private val Clay = Color(0xFF9C6B45)
private val Sand = Color(0xFFF6F1E7)
private val Bark = Color(0xFF2B2A26)
private val Ember = Color(0xFFB3412F)
private val MossDeep = Color(0xFF2F4A34)
private val SageDeep = Color(0xFF4A5A46)
private val BarkSoft = Color(0xFF3A3A34)
private val SandMuted = Color(0xFFC6C6BC)
private val EmberLight = Color(0xFFE0705F)

internal val ProfitRed = Color(0xFFD1453B)
internal val LossBlue = Color(0xFF2F6BD1)

private val LightScheme = lightColorScheme(
    primary = Moss,
    onPrimary = Sand,
    primaryContainer = Sage,
    onPrimaryContainer = Bark,
    secondary = Clay,
    onSecondary = Sand,
    background = Sand,
    onBackground = Bark,
    surface = Sand,
    onSurface = Bark,
    surfaceVariant = Sage,
    onSurfaceVariant = SageDeep,
    error = Ember,
    onError = Sand,
)

private val DarkScheme = darkColorScheme(
    primary = Fern,
    onPrimary = Bark,
    primaryContainer = MossDeep,
    onPrimaryContainer = Sage,
    secondary = Clay,
    onSecondary = Bark,
    background = Bark,
    onBackground = Sand,
    surface = Bark,
    onSurface = Sand,
    surfaceVariant = BarkSoft,
    onSurfaceVariant = SandMuted,
    error = EmberLight,
    onError = Bark,
)

/** 유기적인 곡선 — 기본 M3 보다 확실히 둥글다. */
private val OrganicShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// 숫자가 세로로 정렬되도록 tabular figures 를 켠다 — 표에서 자릿수가 흔들리지 않는다.
private val NhTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontFeatureSettings = "tnum"),
        bodyLarge = base.bodyLarge.copy(fontFeatureSettings = "tnum"),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
        bodySmall = base.bodySmall.copy(fontFeatureSettings = "tnum"),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
fun NhTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = OrganicShapes,
        typography = NhTypography,
        content = content,
    )
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew testDebugUnitTest assembleDebug detekt ktlintCheck --no-daemon
```

기대: FormatTest 4개 PASS, 전체 빌드 성공.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/ui app/src/test/kotlin/dev/nhportfolio/FormatTest.kt
git commit -m "$(cat <<'MSG'
feat(ui): 표시 형식과 organic M3 테마

로케일 고정 숫자 포맷, 이익 빨강/손실 파랑, 비밀을 담지 않는 오류 문구.
자연 톤 팔레트(Moss/Sage/Clay/Sand/Bark)와 둥근 Shapes, tabular 숫자.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 8: 지문 인증과 PIN 화면

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/security/Biometric.kt`
- Create: `app/src/main/kotlin/dev/nhportfolio/lock/LockScreen.kt`

**Interfaces:**
- Consumes: Task 4의 `Vault`(`dek()`·`unlockWith()`·`setPin()`·`unlockWithPin()`)·`K`·`b64()`·`unb64()`, Task 7의 `userMessage()`·`NhTheme`
- Produces:
  - `class Biometric(store, vault)` — `enrolled: Flow<Boolean>`, `suspend enroll(FragmentActivity): Boolean`, `suspend unlock(FragmentActivity): Boolean`, `suspend disable()`, `companion object { fun available(Context): Boolean }`
  - `enum class PinMode { Setup, Verify, Change }`
  - `class LockViewModel(vault: Vault)` — `length`·`title`·`error`·`lockedSeconds`·`busy` 상태, `completed: Flow<Boolean>`, `fun start(PinMode)`, `fun press(Char)`, `fun backspace()`, `fun cancel()`, `var promptedBiometric: Boolean`
  - `@Composable fun PinFlow(mode: PinMode, biometric: Biometric, onDone: (Boolean) -> Unit)`

**검증 방식**: 이 태스크에는 단위 테스트가 없다(BiometricPrompt·Keystore 는 실기기가 필요하고, 사용자가 Compose UI 테스트를 요청하지 않았다). 검증은 `assembleDebug` + `detekt` + `ktlintCheck` 통과이고, 실제 동작은 Task 13의 기기 스모크에서 확인한다.

- [ ] **Step 1: Biometric 작성**

`app/src/main/kotlin/dev/nhportfolio/security/Biometric.kt`:

```kotlin
package dev.nhportfolio.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

private const val BIO_ALIAS = "nh_bio"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val BIO_TAG_BITS = 128
private const val BIO_IV_BYTES = 12
private const val BIO_KEY_BITS = 256

/**
 * 지문으로 DEK 를 여는 경로. 우회가 불가능한 이유는 CryptoObject 의 **출력이 곧 DEK** 이기 때문이다 —
 * 인증 성공 여부(boolean)를 믿는 구조가 아니다.
 *
 * 이 파일 하나를 지우면 지문 기능이 통째로 사라진다. PIN 경로는 영향받지 않는다.
 */
class Biometric(
    private val store: DataStore<Preferences>,
    private val vault: Vault,
) {
    val enrolled: Flow<Boolean> = store.data.map { K.DEK_BIO in it }

    /** 잠금이 풀린 상태에서만 호출한다(UI 는 PIN 재검증 후 호출). 실패해도 예외 대신 false. */
    suspend fun enroll(activity: FragmentActivity): Boolean {
        val dek = vault.dek() ?: return false
        val cipher = runCatching {
            val key = generateBioKey()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull() ?: return false

        val authorised = authenticate(activity, cipher, "지문 등록") ?: return false
        val sealed = runCatching { authorised.iv + authorised.doFinal(dek) }.getOrNull() ?: return false
        store.edit { it[K.DEK_BIO] = sealed.b64() }
        return true
    }

    @Suppress("ReturnCount")
    suspend fun unlock(activity: FragmentActivity): Boolean {
        val blob = (store.data.first()[K.DEK_BIO] ?: return false).unb64()
        val key = bioKey() ?: return false
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BIO_TAG_BITS, blob, 0, BIO_IV_BYTES))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            store.edit { it.remove(K.DEK_BIO) }   // 지문이 새로 등록되어 키가 무효화됐다 — PIN 으로 열고 다시 등록해야 한다
            return false
        } catch (e: GeneralSecurityException) {
            return false
        }

        val authorised = authenticate(activity, cipher, "지문으로 잠금 해제") ?: return false
        val dek = runCatching { authorised.doFinal(blob, BIO_IV_BYTES, blob.size - BIO_IV_BYTES) }.getOrNull()
            ?: return false
        vault.unlockWith(dek)
        return true
    }

    suspend fun disable() {
        store.edit { it.remove(K.DEK_BIO) }
    }

    /** 인증 성공이면 인증된 [Cipher], 취소·실패면 null. */
    private suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText("PIN 사용")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    companion object {
        /** false 면 등록 제안도 설정 토글도 아예 보여주지 않는다 (키 생성이 예외를 던지는 기기가 있다). */
        fun available(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

private fun bioKey(): SecretKey? {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return keyStore.getKey(BIO_ALIAS, null) as? SecretKey
}

/**
 * 사용할 때마다 Class 3 생체 인증을 요구하는 AES 키. 지문이 새로 등록되면 무효화된다.
 * `setUnlockedDeviceRequired` 는 붙이지 않는다 — per-use 생체 키에는 잉여이고
 * 일부 Android 12 기기에서 "device locked" 오류의 원인이다.
 */
private fun generateBioKey(): SecretKey {
    val spec = KeyGenParameterSpec.Builder(
        BIO_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(BIO_KEY_BITS)
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        .setInvalidatedByBiometricEnrollment(true)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply { init(spec) }
        .generateKey()
}
```

- [ ] **Step 2: PIN 화면 작성**

`app/src/main/kotlin/dev/nhportfolio/lock/LockScreen.kt`:

```kotlin
package dev.nhportfolio.lock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.PinResult
import dev.nhportfolio.security.Vault
import dev.nhportfolio.security.weakPin
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

const val PIN_LENGTH = 6

enum class PinMode {
    /** 최초 PIN 설정 — 입력 후 확인. */
    Setup,

    /** 잠금 해제 — 한 번 입력. */
    Verify,

    /** PIN 변경 — 기존 PIN 확인 후 새 PIN 입력·확인. */
    Change,
}

/**
 * PIN 입력 상태를 들고 있는다. 입력한 숫자는 [CharArray] 로만 다루고
 * `rememberSaveable`·`SavedStateHandle` 을 쓰지 않는다 — PIN 이 시스템 saved-state 번들에
 * 직렬화되면 안 되기 때문이다(설정 중 프로세스가 죽으면 설정을 처음부터 다시 한다).
 */
class LockViewModel(private val vault: Vault) : ViewModel() {

    private enum class Phase { VerifyOld, Enter, Confirm }

    private val buffer = CharArray(PIN_LENGTH)
    private var firstEntry: CharArray? = null
    private var phase = Phase.Enter
    private var mode = PinMode.Setup
    private var countdown: Job? = null
    private val completedChannel = Channel<Boolean>(Channel.BUFFERED)

    /** 지문 자동 프롬프트를 화면당 한 번만 띄우기 위한 플래그. 회전에도 살아남는다. */
    var promptedBiometric = false

    val completed: Flow<Boolean> = completedChannel.receiveAsFlow()

    var length by mutableIntStateOf(0)
        private set

    var title by mutableStateOf("")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var lockedSeconds by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    fun start(newMode: PinMode) {
        mode = newMode
        phase = if (newMode == PinMode.Setup) Phase.Enter else Phase.VerifyOld
        clearEntry()
        error = null
        title = when (newMode) {
            PinMode.Setup -> "사용할 PIN 6자리를 입력하세요"
            PinMode.Verify -> "PIN 을 입력하세요"
            PinMode.Change -> "현재 PIN 을 입력하세요"
        }
    }

    fun press(digit: Char) {
        if (busy || lockedSeconds > 0 || length >= PIN_LENGTH) return
        buffer[length++] = digit
        error = null
        if (length == PIN_LENGTH) submit()
    }

    fun backspace() {
        if (busy || length == 0) return
        buffer[--length] = '0'
    }

    fun cancel() {
        clearEntry()
        completedChannel.trySend(false)
    }

    fun onBiometricResult(success: Boolean) {
        if (success) completedChannel.trySend(true) else error = "지문 인증에 실패했습니다"
    }

    override fun onCleared() {
        clearEntry()
        countdown?.cancel()
    }

    private fun submit() {
        val entered = buffer.copyOf(length)
        buffer.fill('0')
        length = 0
        viewModelScope.launch {
            busy = true
            try {
                when (phase) {
                    Phase.VerifyOld -> verify(entered)
                    Phase.Enter -> enterNew(entered)
                    Phase.Confirm -> confirmNew(entered)
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun verify(entered: CharArray) {
        val result = runCatching { vault.unlockWithPin(entered) }.getOrElse {
            error = it.userMessage().ifEmpty { "잠금 해제에 실패했습니다" }
            return
        }
        when (result) {
            is PinResult.Ok ->
                if (mode == PinMode.Verify) {
                    completedChannel.trySend(true)
                } else {
                    phase = Phase.Enter
                    title = "새 PIN 6자리를 입력하세요"
                }
            is PinResult.Wrong -> error = "PIN 이 맞지 않습니다 (남은 시도 ${result.remaining}회)"
            is PinResult.LockedFor -> startCountdown(result.millis)
        }
    }

    private fun enterNew(entered: CharArray) {
        if (weakPin(entered)) {
            entered.fill('0')
            error = "연속되거나 같은 숫자는 쓸 수 없습니다"
            return
        }
        firstEntry?.fill('0')
        firstEntry = entered
        phase = Phase.Confirm
        title = "PIN 을 한 번 더 입력하세요"
    }

    private suspend fun confirmNew(entered: CharArray) {
        val first = firstEntry
        if (first == null || !first.contentEquals(entered)) {
            entered.fill('0')
            first?.fill('0')
            firstEntry = null
            phase = Phase.Enter
            title = "새 PIN 6자리를 입력하세요"
            error = "PIN 이 일치하지 않습니다"
            return
        }
        entered.fill('0')
        runCatching { vault.setPin(first) }          // setPin 이 first 를 지운다
            .onSuccess { completedChannel.trySend(true) }
            .onFailure { error = it.message ?: "PIN 설정에 실패했습니다" }
        firstEntry = null
    }

    private fun startCountdown(millis: Long) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            var left = (millis / 1_000).toInt() + 1
            while (left > 0) {
                lockedSeconds = left
                delay(1_000)
                left--
            }
            lockedSeconds = 0
        }
    }

    private fun clearEntry() {
        buffer.fill('0')
        firstEntry?.fill('0')
        firstEntry = null
        length = 0
    }
}

/**
 * 잠금 해제·PIN 설정·PIN 변경에 모두 쓰는 전체 화면. NavHost 밖 오버레이로 띄우므로
 * Back 으로 빠져나갈 수 없다([BackHandler]).
 */
@Composable
fun PinFlow(
    mode: PinMode,
    biometric: Biometric,
    onDone: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vm: LockViewModel = koinViewModel(),
) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val enrolled by biometric.enrolled.collectAsStateWithLifecycle(false)
    val canUseBiometric = mode == PinMode.Verify && enrolled && activity != null

    LaunchedEffect(mode) { vm.start(mode) }
    LaunchedEffect(Unit) { vm.completed.collect(onDone) }
    // BiometricPrompt 는 컴포저블 스코프에서 띄운다 — viewModelScope 에서 기다리면
    // 회전으로 액티비티가 죽었을 때 영원히 돌아오지 않는다.
    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && !vm.promptedBiometric) {
            vm.promptedBiometric = true
            vm.onBiometricResult(biometric.unlock(activity!!))
        }
    }
    if (mode == PinMode.Verify) BackHandler { /* 잠긴 동안 뒤로가기로 화면을 벗어날 수 없다 */ }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(vm.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { index ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < vm.length) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
            Text(
                text = when {
                    vm.lockedSeconds > 0 -> "${vm.lockedSeconds}초 후에 다시 시도할 수 있습니다"
                    else -> vm.error.orEmpty()
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.size(24.dp))
            PinPad(
                enabled = !vm.busy && vm.lockedSeconds == 0,
                onDigit = vm::press,
                onBackspace = vm::backspace,
            )

            if (canUseBiometric) {
                TextButton(onClick = {
                    scope.launch { vm.onBiometricResult(biometric.unlock(activity!!)) }
                }) {
                    Text("지문으로 잠금 해제")
                }
            }
            if (mode == PinMode.Change) {
                TextButton(onClick = vm::cancel) { Text("취소") }
            }
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("123", "456", "789", " 0<").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    when (key) {
                        ' ' -> Spacer(Modifier.size(72.dp))
                        '<' -> PinKey("⌫", enabled) { onBackspace() }
                        else -> PinKey(key.toString(), enabled) { onDigit(key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
```

- [ ] **Step 3: 빌드와 정적분석 통과 확인**

```bash
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest --no-daemon
```

기대: `BUILD SUCCESSFUL`. 자주 걸리는 곳 — `LocalActivity` 는 `androidx.activity.compose.LocalActivity`(activity-compose 1.9+)이고, `collectAsStateWithLifecycle` 은 `androidx.lifecycle.compose` 에서 온다.

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/security/Biometric.kt app/src/main/kotlin/dev/nhportfolio/lock
git commit -m "$(cat <<'MSG'
feat(lock): 지문 인증과 PIN 화면

CryptoObject 의 출력이 곧 DEK 라 지문 우회 경로가 없다. 지문 재등록으로
키가 무효화되면 등록 정보를 지우고 PIN 으로 되돌린다. PIN 은 CharArray 로만
다루고 saved-state 에 넣지 않는다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 9: 설정 화면

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: Task 4의 `Vault`, Task 8의 `Biometric`·`PinMode`, Task 7의 `userMessage()`
- Produces:
  - `class SettingsViewModel(vault: Vault, biometric: Biometric)` — `hasKeys: StateFlow<Boolean?>`, `bioEnrolled: StateFlow<Boolean>`, `error`·`saved` 상태, `fun save(appKey: String, appSecret: String)`, `fun disableBiometric()`, `fun wipe()`
  - `@Composable fun SettingsScreen(requestPin: (PinMode, (Boolean) -> Unit) -> Unit, onBack: (() -> Unit)?, modifier: Modifier)` — `onBack` 이 null 이면 "키 없음" 게이트 단계라 뒤로가기 버튼을 그리지 않는다

- [ ] **Step 1: 화면 작성**

`app/src/main/kotlin/dev/nhportfolio/settings/SettingsScreen.kt`:

```kotlin
package dev.nhportfolio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.lock.PinMode
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 앱키·시크릿에 허용하는 문자 범위(공백·제어문자 배제). */
private val PRINTABLE = 33..126

class SettingsViewModel(
    private val vault: Vault,
    private val biometric: Biometric,
) : ViewModel() {

    val hasKeys: StateFlow<Boolean?> = vault.secretsFlow
        .map { it.appKey != null }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bioEnrolled: StateFlow<Boolean> = biometric.enrolled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var error by mutableStateOf<String?>(null)
        private set

    var saved by mutableStateOf(false)
        private set

    /**
     * 값을 그대로 저장하되, 키가 바뀌었을 때만 토큰을 버린다 —
     * 같은 키를 다시 붙여넣었다고 재발급이 일어나면 NH 보안 알림이 쌓인다.
     */
    fun save(rawKey: String, rawSecret: String) {
        val appKey = rawKey.trim()
        val appSecret = rawSecret.trim()
        when {
            appKey.isEmpty() || appSecret.isEmpty() -> {
                error = "앱 키와 시크릿을 모두 입력하세요"
                return
            }
            !appKey.all { it.code in PRINTABLE } || !appSecret.all { it.code in PRINTABLE } -> {
                error = "앱 키에 공백이나 줄바꿈이 섞여 있습니다"
                return
            }
        }
        viewModelScope.launch {
            runCatching {
                vault.update { current ->
                    val unchanged = current.appKey == appKey && current.appSecret == appSecret
                    current.copy(
                        appKey = appKey,
                        appSecret = appSecret,
                        token = if (unchanged) current.token else null,
                        tokenIssuedAt = if (unchanged) current.tokenIssuedAt else 0,
                        tokenExpiresAt = if (unchanged) current.tokenExpiresAt else 0,
                    )
                }
            }.onSuccess {
                error = null
                saved = true
            }.onFailure {
                error = it.userMessage().ifEmpty { "저장하지 못했습니다" }
            }
        }
    }

    fun enrollBiometric(activity: FragmentActivity) {
        viewModelScope.launch {
            if (!biometric.enroll(activity)) error = "지문을 등록하지 못했습니다"
        }
    }

    fun disableBiometric() {
        viewModelScope.launch { biometric.disable() }
    }

    fun wipe() {
        viewModelScope.launch { vault.wipe() }
    }
}

@Composable
fun SettingsScreen(
    requestPin: (PinMode, (Boolean) -> Unit) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val hasKeys by vm.hasKeys.collectAsStateWithLifecycle()
    val bioEnrolled by vm.bioEnrolled.collectAsStateWithLifecycle()
    val biometricAvailable = remember { Biometric.available(context) }

    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NH PLUG 앱 키", style = MaterialTheme.typography.titleMedium)
            AssistChip(
                onClick = { },
                label = { Text(if (hasKeys == true) "저장됨" else "미설정") },
            )
            Text(
                "저장된 값은 다시 보여주지 않습니다. 바꾸려면 새로 입력해 저장하세요.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("appkey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("appsecretkey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    vm.save(appKey, appSecret)
                    appKey = ""
                    appSecret = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장") }

            HorizontalDivider()

            if (biometricAvailable && activity != null) {
                ListItem(
                    headlineContent = { Text("지문으로 잠금 해제") },
                    trailingContent = {
                        Switch(
                            checked = bioEnrolled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    requestPin(PinMode.Verify) { ok -> if (ok) vm.enrollBiometric(activity) }
                                } else {
                                    vm.disableBiometric()
                                }
                            },
                        )
                    },
                )
            }
            ListItem(
                headlineContent = { Text("PIN 변경") },
                trailingContent = {
                    TextButton(onClick = { requestPin(PinMode.Change) { } }) { Text("변경") }
                },
            )
            ListItem(
                headlineContent = { Text("앱 초기화") },
                supportingContent = { Text("앱 키·토큰·목표 비중·PIN 을 모두 지웁니다") },
                trailingContent = {
                    TextButton(onClick = { confirmWipe = true }) {
                        Text("초기화", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("앱을 초기화할까요?") },
            text = { Text("저장된 앱 키와 목표 비중, PIN 이 모두 지워집니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch { vm.wipe() }
                }) { Text("초기화", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("취소") } },
        )
    }
}
```

- [ ] **Step 2: 빌드와 정적분석 통과 확인**

```bash
./gradlew assembleDebug detekt ktlintCheck --no-daemon
```

기대: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/settings
git commit -m "$(cat <<'MSG'
feat(settings): 앱 키 입력(write-only), 지문 토글, PIN 변경, 초기화

저장된 값은 절대 다시 보여주지 않고 상태만 표시한다. 같은 키를 다시
저장해도 토큰을 버리지 않는다(불필요한 재발급 방지).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 10: 계좌 목록 화면

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/accounts/AccountsScreen.kt`

**Interfaces:**
- Consumes: Task 5의 `NhApi.accounts()`·`loadResult`, Task 4의 `Vault.secretsFlow`, Task 7의 `userMessage()`
- Produces:
  - `class AccountsViewModel(vault: Vault, api: NhApi)` — `ui: StateFlow<Result<List<Account>>?>`(null = 로딩), `fun retry()`
  - `@Composable fun AccountsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, modifier: Modifier)`

- [ ] **Step 1: 화면 작성**

`app/src/main/kotlin/dev/nhportfolio/accounts/AccountsScreen.kt`:

```kotlin
package dev.nhportfolio.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.security.Vault
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel

class AccountsViewModel(vault: Vault, api: NhApi) : ViewModel() {

    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** null = 아직 로딩 중. 앱 키가 바뀌면 자동으로 다시 조회한다. */
    val ui: StateFlow<Result<List<Account>>?> = merge(
        vault.secretsFlow.map { it.appKey }.filterNotNull().distinctUntilChanged().map { },
        kick,
    )
        .mapLatest { loadResult { api.accounts() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() {
        kick.tryEmit(Unit)
    }
}

@Composable
fun AccountsScreen(
    onOpen: (acctNo: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AccountsViewModel = koinViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("계좌") },
                actions = { TextButton(onClick = onSettings) { Text("설정") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val result = state
            when {
                result == null -> CircularProgressIndicator()

                result.isFailure -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(result.exceptionOrNull()!!.userMessage())
                    TextButton(onClick = vm::retry) { Text("다시 시도") }
                }

                result.getOrThrow().isEmpty() -> Text("연결된 계좌가 없습니다")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(result.getOrThrow(), key = { it.no }) { account ->
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { onOpen(account.no) },
                        ) {
                            Text(
                                text = account.no,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 빌드와 정적분석 통과 확인**

```bash
./gradlew assembleDebug detekt ktlintCheck --no-daemon
```

기대: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/accounts
git commit -m "$(cat <<'MSG'
feat(accounts): 운영 계좌 목록 화면

앱 키가 바뀌면 자동 재조회, 실패는 재시도 버튼, 빈 목록은 안내 문구.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 11: 포트폴리오 화면

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt`

**Interfaces:**
- Consumes: Task 2의 `Rebalance`·`Balance`·`Holding`·`Fill`, Task 5의 `NhApi.balance()`·`loadResult`, Task 6의 `NhApi.fills()`, Task 7의 포맷 함수
- Produces:
  - `data class PortfolioUi(balance: Balance?, plan: Rebalance.Plan?, lastFill: Fill?, error: String?)` — `balance == null && error == null` 이면 로딩
  - `class PortfolioViewModel(acctNo: String, api: NhApi, store: DataStore<Preferences>)` — `ui: StateFlow<PortfolioUi>`, `fun refresh()`, `fun setTarget(code: String, bp: Int?)`
  - `@Composable fun PortfolioScreen(acctNo: String, onBack: () -> Unit, modifier: Modifier)`

- [ ] **Step 1: 화면 작성**

`app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt`:

```kotlin
package dev.nhportfolio.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Fill
import dev.nhportfolio.ui.bpPct
import dev.nhportfolio.ui.krw
import dev.nhportfolio.ui.pct
import dev.nhportfolio.ui.plColor
import dev.nhportfolio.ui.shares
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.security.MessageDigest
import kotlin.math.roundToInt

private const val FILL_DEBOUNCE_MS = 300L
private const val FULL_BP = 10_000
private val TARGET_INPUT = Regex("""^\d{1,3}(\.\d{1,2})?$""")

/** [balance] 와 [error] 가 모두 null 이면 최초 로딩. 오류가 나도 마지막 정상 표는 유지한다. */
data class PortfolioUi(
    val balance: Balance? = null,
    val plan: Rebalance.Plan? = null,
    val lastFill: Fill? = null,
    val error: String? = null,
)

class PortfolioViewModel(
    acctNo: String,
    private val api: NhApi,
    private val store: DataStore<Preferences>,
) : ViewModel() {

    private val account = Account(acctNo)
    private val targetsKey = targetsKey(acctNo)
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val lastFill = MutableStateFlow<Fill?>(null)

    // 재조회는 사용자 범위의 **모든** 체결통보가 트리거한다(토큰에 묶인 채널이라 계좌 필터가 필요 없다).
    // 계좌 매칭은 스낵바 표시에만 쓰므로 accountno 형식이 달라도 기능이 죽지 않는다.
    private val fills = api.fills().onEach { fill ->
        if (fill.acctNo.filter(Char::isDigit) == acctNo.filter(Char::isDigit)) lastFill.value = fill
    }

    private val loads = merge(flowOf(Unit), kick, fills.map { }.debounce(FILL_DEBOUNCE_MS))
        .mapLatest { loadResult { api.balance(account) } }
        .runningFold(null as Balance? to null as String?) { (last, _), result ->
            result.fold({ it to null }, { last to it.userMessage() })
        }
        .drop(1)

    val ui: StateFlow<PortfolioUi> = combine(
        loads,
        store.data.map { readTargets(it, targetsKey) },
        lastFill,
    ) { (balance, error), targets, fill ->
        PortfolioUi(balance, balance?.let { Rebalance.plan(it, targets) }, fill, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUi())

    fun refresh() {
        kick.tryEmit(Unit)
    }

    /** [bp] 가 null 이면 목표를 지운다. 범위 밖 값은 호출 전에 걸러진다. */
    fun setTarget(code: String, bp: Int?) {
        require(bp == null || bp in 0..FULL_BP) { "목표 비중은 0~100% 범위여야 합니다" }
        viewModelScope.launch {
            store.edit { prefs ->
                val next = readTargets(prefs, targetsKey).toMutableMap()
                if (bp == null) next -= code else next[code] = bp
                prefs[targetsKey] = Json.encodeToString(next)
            }
        }
    }
}

/** 계좌번호를 평문으로 디스크에 쓰지 않는다. */
private fun targetsKey(acctNo: String): Preferences.Key<String> {
    val digest = MessageDigest.getInstance("SHA-256").digest(acctNo.toByteArray())
    return stringPreferencesKey("targets_" + digest.joinToString("") { "%02x".format(it) }.take(16))
}

/** 저장값이 깨졌거나 범위를 벗어나도 화면이 죽지 않는다. */
private fun readTargets(prefs: Preferences, key: Preferences.Key<String>): Map<String, Int> =
    runCatching { Json.decodeFromString<Map<String, Int>>(prefs[key] ?: "{}") }
        .getOrDefault(emptyMap())
        .filterValues { it in 0..FULL_BP }

@Composable
fun PortfolioScreen(
    acctNo: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: PortfolioViewModel = koinViewModel { parametersOf(acctNo) },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var rebalanceMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, Int?>?>(null) }

    LaunchedEffect(ui.lastFill) {
        ui.lastFill?.let { fill ->
            val at = fill.time.take(4).chunked(2).joinToString(":")
            snackbar.showSnackbar("${fill.name} ${fill.qty.shares()}주 체결 @${fill.price.krw()} ($at)")
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(acctNo) },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = { TextButton(onClick = vm::refresh) { Text("새로고침") } },
            )
        },
    ) { padding ->
        val balance = ui.balance
        val plan = ui.plan
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                balance == null && ui.error != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(ui.error.orEmpty())
                    TextButton(onClick = vm::refresh) { Text("다시 시도") }
                }

                balance == null || plan == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                else -> Column(Modifier.fillMaxSize()) {
                    ui.error?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    SummaryCard(plan)
                    ModeSelector(rebalanceMode) { rebalanceMode = it }
                    HoldingsList(
                        balance = balance,
                        plan = plan,
                        rebalanceMode = rebalanceMode,
                        onEdit = { code, bp -> editing = code to bp },
                    )
                }
            }
        }
    }

    editing?.let { (code, currentBp) ->
        val name = ui.balance?.holdings?.firstOrNull { it.code == code }?.name
            ?: if (code == Rebalance.CASH) "예수금" else code
        TargetDialog(
            name = name,
            currentBp = currentBp,
            onDismiss = { editing = null },
            onSet = { bp ->
                vm.setTarget(code, bp)
                editing = null
            },
        )
    }
}

@Composable
private fun SummaryCard(plan: Rebalance.Plan) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("총 평가", style = MaterialTheme.typography.bodySmall)
            Text(plan.total.krw(), style = MaterialTheme.typography.titleMedium)
            Text("예수금(D+2) ${plan.lines.last().currentAmt.krw()}", style = MaterialTheme.typography.bodySmall)

            val sum = plan.targetSumBp
            if (sum > 0) {
                Text(
                    text = when {
                        sum > FULL_BP -> "목표 합계 ${sum.bpPct()} — 100% 를 넘습니다"
                        sum < FULL_BP -> "목표 합계 ${sum.bpPct()}"
                        else -> "목표 합계 100.00%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sum > FULL_BP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.cashAfter < 0) {
                Text(
                    "매매 후 예수금 ${plan.cashAfter.krw()} — 예수금이 부족합니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(rebalanceMode: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SegmentedButton(
            selected = !rebalanceMode,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("보유") }
        SegmentedButton(
            selected = rebalanceMode,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("리밸런스") }
    }
}

@Composable
private fun HoldingsList(
    balance: Balance,
    plan: Rebalance.Plan,
    rebalanceMode: Boolean,
    onEdit: (code: String, currentBp: Int?) -> Unit,
) {
    val byCode = remember(balance) { balance.holdings.associateBy { it.code } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(plan.lines, key = { it.code }) { line ->
            val holding = byCode[line.code]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(line.code, line.targetBp) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(holding?.name ?: "예수금", style = MaterialTheme.typography.titleMedium)
                    Text(line.currentAmt.krw(), style = MaterialTheme.typography.titleMedium)
                }
                if (rebalanceMode) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${line.weightBp.bpPct()} → ${line.targetBp?.bpPct() ?: "목표 없음"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = when (val delta = line.deltaShares) {
                                null -> "—"
                                0L -> "유지"
                                else -> if (delta > 0) "${delta.shares()}주 매수" else "${(-delta).shares()}주 매도"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                (line.deltaShares ?: 0L) > 0 -> MaterialTheme.colorScheme.primary
                                (line.deltaShares ?: 0L) < 0 -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                } else if (holding != null) {
                    Text(
                        "보유 ${holding.qty.shares()}주 · 잔고 ${holding.remainQty.shares()}주 · " +
                            "평균 ${holding.avgPrice.krw()} · 현재 ${holding.price.krw()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(holding.pnlRate.pct(), color = plColor(holding.pnlRate), style = MaterialTheme.typography.bodyMedium)
                        Text("비중 ${line.weightBp.bpPct()}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("비중 ${line.weightBp.bpPct()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TargetDialog(
    name: String,
    currentBp: Int?,
    onDismiss: () -> Unit,
    onSet: (Int?) -> Unit,
) {
    var text by remember {
        mutableStateOf(currentBp?.let { bp -> (bp / 100.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } }.orEmpty())
    }
    val parsedBp = text.trim()
        .takeIf { it.matches(TARGET_INPUT) }
        ?.toDouble()
        ?.let { (it * 100).roundToInt() }
        ?.takeIf { it in 0..FULL_BP }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name 목표 비중") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("퍼센트 (0 ~ 100)") },
                    singleLine = true,
                    isError = text.isNotBlank() && parsedBp == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (text.isNotBlank() && parsedBp == null) {
                    Text("0 ~ 100 사이 숫자를 입력하세요", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsedBp != null, onClick = { onSet(parsedBp) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (currentBp != null) TextButton(onClick = { onSet(null) }) { Text("목표 삭제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
```

> 사양의 pull-to-refresh 대신 상단바 "새로고침" 버튼을 쓴다. `PullToRefreshBox` 는 별도의 `isRefreshing` 상태를 손으로 관리해야 하는데(같은 잔고가 다시 와도 스피너를 꺼야 한다) 버튼은 상태가 0개다. 체결 자동 반영은 소켓이 하므로 수동 새로고침의 빈도는 낮다.

- [ ] **Step 2: 빌드와 정적분석 통과 확인**

```bash
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest --no-daemon
```

기대: `BUILD SUCCESSFUL`, 기존 테스트 전부 PASS.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt
git commit -m "$(cat <<'MSG'
feat(portfolio): 보유 목록과 리밸런스 화면

체결통보 수신 시 300ms 디바운스 후 잔고 재조회, 오류가 나도 마지막 정상
표를 유지한다. 목표 비중은 계좌별로 저장(계좌번호는 해시), 입력은 0~100%
로 검증하고 깨진 저장값은 무시한다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 12: 배선 — Koin, 잠금 정책, 게이트, 내비게이션

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/App.kt`
- Create: `app/src/main/kotlin/dev/nhportfolio/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`<application android:name>` 과 `<activity>` 추가)

**Interfaces:**
- Consumes: Task 4·5·6·7·8·9·10·11의 모든 공개 타입
- Produces: 실행 가능한 앱. `Route` 정의(`Accounts`·`Portfolio(no)`·`Settings`), `appModule`(Koin), 60초 잠금 정책

- [ ] **Step 1: Application 과 Koin 모듈 작성**

`app/src/main/kotlin/dev/nhportfolio/App.kt`:

```kotlin
package dev.nhportfolio

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.nhportfolio.accounts.AccountsViewModel
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.lock.LockViewModel
import dev.nhportfolio.portfolio.PortfolioViewModel
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** 백그라운드에 이만큼 머물렀다 돌아오면 잠근다. */
private const val LOCK_AFTER_MS = 60_000L

// corruptionHandler 가 없으면 파일이 한 번 깨졌을 때 모든 실행이 CorruptionException 으로 죽는다.
// 초기화와 결과가 같다(래핑된 비밀은 어차피 복구 불가) — 크래시 루프 대신 Setup 게이트로 떨어진다.
private val Context.nhStore: DataStore<Preferences> by preferencesDataStore(
    name = "nh",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

val appModule = module {
    single { androidContext().nhStore }
    single {
        Vault(
            store = get(),
            bootCount = {
                Settings.Global.getInt(androidContext().contentResolver, Settings.Global.BOOT_COUNT, 0)
            },
        )
    }
    single { Biometric(get(), get()) }
    single { NhApi(get()) }

    viewModelOf(::LockViewModel)
    viewModelOf(::AccountsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { (acctNo: String) -> PortfolioViewModel(acctNo, get(), get()) }
}

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@App)
            modules(appModule)
        }.koin
        val vault = koin.get<Vault>()

        // 타이머를 걸지 않고 "돌아왔을 때 얼마나 지났나" 로 판정한다 —
        // delay 는 딥슬립 중에 흐르지 않아서 화면을 끄고 30분 뒤 돌아와도 안 잠기는 문제가 있다.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                private var stoppedAt = 0L

                override fun onStop(owner: LifecycleOwner) {
                    stoppedAt = SystemClock.elapsedRealtime()
                }

                override fun onStart(owner: LifecycleOwner) {
                    if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= LOCK_AFTER_MS) {
                        vault.lock()
                    }
                }
            },
        )
    }
}
```

- [ ] **Step 2: MainActivity 와 게이트 작성**

`app/src/main/kotlin/dev/nhportfolio/MainActivity.kt`:

```kotlin
package dev.nhportfolio

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.nhportfolio.accounts.AccountsScreen
import dev.nhportfolio.lock.PinFlow
import dev.nhportfolio.lock.PinMode
import dev.nhportfolio.portfolio.PortfolioScreen
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.settings.SettingsScreen
import dev.nhportfolio.ui.NhTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** 타입 세이프 내비게이션 경로. 잠금 화면과 "키 없음" 화면은 Route 가 아니라 게이트 단계다. */
@Serializable
sealed interface Route {
    @Serializable
    data object Accounts : Route

    @Serializable
    data class Portfolio(val no: String) : Route

    @Serializable
    data object Settings : Route
}

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 화면 캡처·최근 앱 썸네일 차단, 오버레이를 통한 탭재킹 차단, 서드파티 자동완성이
        // appsecret 저장을 제안하지 못하게 막기.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent { NhTheme { AppNav() } }
    }
}

/**
 * 게이트 상태기계. `NavHost` 의 시작 목적지는 항상 [Route.Accounts] 라서
 * 잠금·회전·프로세스 복원에도 백스택이 보존된다. "키 없음" 은 Route 가 아니라
 * 게이트 단계로 [SettingsScreen] 을 단독으로 그린다.
 */
@Composable
private fun AppNav() {
    val vault: Vault = koinInject()
    val biometric: Biometric = koinInject()
    val scope = rememberCoroutineScope()

    var corrupt by remember { mutableStateOf(false) }
    var pinRequest by remember { mutableStateOf<Pair<PinMode, (Boolean) -> Unit>?>(null) }

    val hasPin by remember { vault.hasPin.catch { corrupt = true } }.collectAsStateWithLifecycle(null)
    val unlocked by vault.unlocked.collectAsStateWithLifecycle()
    val hasKeys by remember(unlocked) {
        vault.secretsFlow.map { it.appKey != null }.catch { corrupt = true }
    }.collectAsStateWithLifecycle(null)

    val nav = rememberNavController()

    if (corrupt) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("저장된 데이터가 손상되었습니다") },
            text = { Text("앱 키와 PIN 을 다시 설정해야 합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    corrupt = false
                    scope.launch { vault.wipe() }
                }) { Text("초기화") }
            },
        )
        return
    }

    when {
        // DataStore 첫 읽기 전에는 아무것도 그리지 않는다 (화면 깜빡임 방지)
        hasPin == null || (unlocked && hasKeys == null) -> Unit

        hasPin == false -> PinFlow(PinMode.Setup, biometric, onDone = { })

        !unlocked -> PinFlow(PinMode.Verify, biometric, onDone = { })

        else -> Box {
            if (hasKeys == false) {
                SettingsScreen(
                    requestPin = { mode, callback -> pinRequest = mode to callback },
                    onBack = null,
                )
            } else {
                NavHost(navController = nav, startDestination = Route.Accounts) {
                    composable<Route.Accounts> {
                        AccountsScreen(
                            onOpen = { nav.navigate(Route.Portfolio(it)) },
                            onSettings = { nav.navigate(Route.Settings) },
                        )
                    }
                    composable<Route.Portfolio> { entry ->
                        PortfolioScreen(
                            acctNo = entry.toRoute<Route.Portfolio>().no,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable<Route.Settings> {
                        SettingsScreen(
                            requestPin = { mode, callback -> pinRequest = mode to callback },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
            // 설정에서 띄우는 PIN 확인·변경도 같은 전체화면 슬롯을 쓴다 (별도 창 없음).
            pinRequest?.let { (mode, callback) ->
                PinFlow(mode, biometric, onDone = { ok ->
                    pinRequest = null
                    callback(ok)
                })
            }
        }
    }
}
```

- [ ] **Step 3: 매니페스트에 Application 과 Activity 등록**

`app/src/main/AndroidManifest.xml` 의 `<application>` 에 `android:name=".App"` 을 추가하고, 자식으로 액티비티를 넣는다.

```xml
    <application
        android:name=".App"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="false"
        android:theme="@style/Theme.NhPortfolio">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.NhPortfolio"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
```

- [ ] **Step 4: 빌드하고 실제로 실행**

```bash
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.nhportfolio/.MainActivity
```

기대: 빌드 성공, 앱이 실행되어 **PIN 설정 화면**이 뜬다. PIN 6자리 입력 → 확인 → 설정 화면(키 미설정) 이 나오는 것까지 확인한다. 기기가 없으면 에뮬레이터(`emulator -avd <name>`)를 쓴다.

문제가 생기면 `adb logcat -s AndroidRuntime:E` 로 스택트레이스를 본다. Koin 주입 오류(`NoBeanDefFoundException`)면 `appModule` 의 정의와 생성자 시그니처가 맞는지 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/App.kt app/src/main/kotlin/dev/nhportfolio/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "$(cat <<'MSG'
feat: 앱 배선 - Koin, 잠금 정책, 게이트, 내비게이션

시작 목적지는 항상 계좌 목록이라 잠금·회전·프로세스 복원에도 백스택이
남는다. 잠금은 타이머가 아니라 복귀 시 경과 시간으로 판정한다(딥슬립 대응).
DataStore 손상은 크래시 대신 초기화 안내로 떨어진다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## Task 13: 릴리스 빌드 검증과 문서

**Files:**
- Create: `README.md`
- Modify: `app/proguard-rules.pro` (필요한 keep 규칙이 더 있으면)

**Interfaces:**
- Consumes: 전체
- Produces: R8 로 축소된 릴리스 APK 가 실제로 동작한다는 증거와, 위협 모델·스모크 체크리스트 문서

- [ ] **Step 1: 릴리스 빌드**

```bash
./gradlew assembleRelease lintVitalRelease --no-daemon
ls -la app/build/outputs/apk/release/
ls -la app/build/outputs/mapping/release/
```

기대: `app-release-unsigned.apk` 생성, `mapping.txt` 생성.

- [ ] **Step 2: 릴리스 APK 를 디버그 키로 서명해 설치**

R8 full mode 는 `private @Serializable` DTO·reified `NhResponse`·`@Serializable Route` 를 깨뜨릴 수 있다. 반드시 축소된 APK 로 확인한다.

```bash
SDK="$LOCALAPPDATA/Android/Sdk"
"$SDK/build-tools/37.0.0/apksigner.bat" sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out /tmp/release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r /tmp/release-signed.apk
adb shell am start -n dev.nhportfolio/.MainActivity
```

`~/.android/debug.keystore` 가 없으면 한 번 debug 빌드를 설치하면 생성된다. build-tools 버전은 실제 설치된 것으로 바꾼다.

전 화면을 눌러 본다: PIN 설정 → 설정에서 앱 키 저장 → 계좌 목록 → 포트폴리오 → 리밸런스 탭 → 목표 비중 입력. `adb logcat -s AndroidRuntime:E` 에 예외가 없어야 한다. `SerializationException` 이 뜨면 `proguard-rules.pro` 에 해당 클래스 keep 을 추가하고 다시 빌드한다.

- [ ] **Step 3: 릴리스 APK 에 로그·비밀이 없는지 확인**

```bash
unzip -p /tmp/release-signed.apk classes.dex | strings | grep -iE 'appsecretkey|APPKEY|Bearer ' | head
```

기대: `appsecretkey` 는 토큰 요청의 파라미터 이름이라 문자열 상수로 남는 게 정상이다. **실제 키 값**이나 `Log.d` 호출 흔적(`NhApi rsp_cd=`)이 남지 않았는지 본다.

- [ ] **Step 4: README 작성**

`README.md`:

```markdown
# NH Portfolio

NH투자증권 나무 계좌의 보유 종목과 자산 비중을 보고, 목표 비중을 입력하면
매수/매도해야 할 주식 수를 계산해 주는 개인용 Android 앱. **거래 기능은 없다.**

## 빌드

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # 또는 다른 JDK 21
./gradlew assembleDebug
./gradlew ktlintFormat ktlintCheck detekt testDebugUnitTest
```

compileSdk 37 이 필요하다. 없으면 `sdkmanager "platforms;android-37"` 로 설치한다.

## 설정

1. <https://www.nhplug.com> 에서 앱 키(appkey)와 시크릿(appsecretkey)을 발급받는다.
2. 앱 최초 실행 시 6자리 PIN 을 만든다. 지문을 등록할 수 있으면 등록을 제안한다.
3. 설정 화면에 앱 키와 시크릿을 입력하고 저장한다. 저장한 값은 다시 보이지 않는다.

운영(실계좌) 환경만 쓴다. 모의투자 계좌(acct_type 03)는 목록에 나오지 않는다.

## 보안 — 위협 모델

무엇을 막고 무엇을 못 막는지 정확히 적는다.

| 상황 | 결과 |
|---|---|
| DataStore 파일만 복사 (백업·adb·다른 앱) | **오프라인 공격 불가.** PIN 을 감싸는 HMAC 키가 AndroidKeyStore 를 떠나지 못한다. |
| 루팅된 기기 + 기기 잠금 해제 상태 + 앱은 잠김 | 상한은 **10⁶ 회의 Keystore HMAC 호출** (TEE 약 20분~3시간, StrongBox 약 1~2일). PBKDF2 는 salt·반복수가 파일에 있어 오프디바이스로 미리 계산되므로 상한이 아니다. |
| 기기 자체가 잠긴 상태 | `setUnlockedDeviceRequired` 가 HMAC 키 사용을 막는다. |
| 앱 잠금이 풀린 동안의 메모리 덤프 | **전부 노출된다.** 이 앱이 막을 수 있는 범위가 아니다. |
| 지문 우회 시도 | 불가능. CryptoObject 의 **출력이 곧 DEK** 라 인증 성공 여부(boolean)를 속여도 얻는 게 없다. |
| 화면 캡처·최근 앱 썸네일 | `FLAG_SECURE` 로 차단. |
| 오버레이를 통한 탭재킹 | `setHideOverlayWindows` + `filterTouchesWhenObscured` 로 차단. |
| 클라우드 백업·기기 이전 | `allowBackup=false` + `dataExtractionRules` 로 전부 제외. |
| 루팅 탐지 | **하지 않는다.** 우회 가능하고 Keystore 결속 이상의 이득이 없다. |

로그·화면·예외 메시지에 앱 키·시크릿·토큰을 절대 넣지 않는다. `ktor-client-logging`
의존성 자체가 없고, 릴리스 빌드는 R8 이 `Log.d`/`Log.v` 를 제거한다.

PIN 을 5회 틀리면 30초 잠기고 실패마다 두 배로 늘어난다(상한 1시간). 자동 초기화는
하지 않는다 — 실수로 데이터를 잃는 쪽이 더 나쁘다. PIN 을 잊었다면 설정에서 초기화한다.

## 기기 스모크 체크리스트 (릴리스마다 1회)

축소된 릴리스 APK 로 확인한다(디버그 빌드로는 R8 문제를 못 잡는다).

- [ ] 최초 실행 → PIN 설정 → 지문 등록 제안 → 설정 화면
- [ ] 앱 키 저장 → 계좌 목록 → 포트폴리오 (토큰 발급이 **1회**인지 로그로 확인)
- [ ] 앱을 껐다 켰을 때 토큰 재발급이 일어나지 않는다
- [ ] 나무 앱에서 수동 매매 → 1초 안에 스낵바 + 잔고 갱신 + 예수금 즉시 감소
- [ ] 5분 이상 무거래 유지 → 재연결 로그 0건 (WebSocket ping/pong)
- [ ] 목표 비중 입력 → 매수/매도 주식 수 표시, 합계 초과/미달 배지
- [ ] 화면 끄고 2분 후 복귀 → 잠금 화면
- [ ] 지문으로 잠금 해제 / 지문 재등록 후 PIN 으로 해제 + 재등록 안내
- [ ] PIN 5회 오입력 → 30초 잠금, 재부팅해도 잠금 유지
- [ ] 스크린샷 차단, 최근 앱 썸네일 가려짐
- [ ] 비행기 모드에서 실행 → "네트워크 오류" (인증 실패가 아니어야 한다)
- [ ] 다크 모드 전환

## 사양·계획

- 설계: `docs/superpowers/specs/2026-08-30-nh-portfolio-design.md`
- 구현 계획: `docs/superpowers/plans/2026-08-30-nh-portfolio.md`

설계 §14 에 실기기에서 확인해야 할 항목(체결통보 프레임 형태, `pft_rt` 단위,
`nxt2_dd_dca` 의 당일 반영 여부 등)이 남아 있다.
```

- [ ] **Step 5: 최종 검증**

```bash
./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug assembleRelease --no-daemon
git status --short
```

기대: 전부 통과, 작업 트리 깨끗(README 커밋 전이면 그것만 보임).

- [ ] **Step 6: 커밋**

```bash
git add README.md app/proguard-rules.pro
git commit -m "$(cat <<'MSG'
docs: README - 위협 모델과 기기 스모크 체크리스트

무엇을 막고 무엇을 못 막는지 정확히 적는다. PBKDF2 는 루팅 공격의
상한이 아니며 실제 상한은 Keystore HMAC 호출 횟수라는 점을 명시.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JL8MC63Ly8zG8nWw5wTwvx
MSG
)"
```

---

## 자기 점검 (계획 작성 후 수행함)

**사양 커버리지** — 사양 14개 절 전부에 대응하는 태스크가 있다.

| 사양 절 | 태스크 |
|---|---|
| §1 아키텍처 개요 / §2 패키지 레이아웃 | Task 1(골격), 이후 전 태스크가 그 배치를 따른다 |
| §3 핵심 타입 | Task 2(model·Rebalance), 3·4(Vault), 5·6(NhApi), 7(ui), 8(lock), 11(portfolio), 12(App) |
| §4 데이터 흐름 | Task 10(계좌), 11(잔고·체결·목표), 12(게이트) |
| §5 NH API 클라이언트 규칙 | Task 5 (토큰·401·429·성공 판정·cts) |
| §6 리밸런스 계산 | Task 2 |
| §7 보안 | Task 3·4(Vault·잠금), 8(지문), 9(write-only 설정), 12(FLAG_SECURE·탭재킹·자동완성·잠금 정책) |
| §8 실시간 + 서비스 seam | Task 6 |
| §9 해외주식 seam | 코드 변경 없음 — `CHANNELS`·`NhResponse`·`Holding` 계약이 Task 2·5·6에서 그대로 만들어진다 |
| §10 화면·내비게이션·테마 | Task 7(테마), 8·9·10·11(화면), 12(내비게이션) |
| §11 에러 처리 | Task 5(`loadResult`·`NhException`), 7(`userMessage`), 11(배너·마지막 정상 표) |
| §12 테스트·CI | Task 1(CI·정적분석), 2~7(단위 테스트), 13(릴리스 검증) |
| §13 의존성 | Task 1 |
| §14 미해결 항목 | Task 13 README 스모크 체크리스트 |

**요구 기능 커버리지**: 계좌 목록(10) · 보유 종목 8개 항목(11) · 목표 비중 입력과 매수/매도 주식 수(2·11) · 거래 즉시 반영(6·11) · PIN + 지문 로그인(4·8·12) · 설정 화면 자격증명(9) · organic 디자인(7) · 단위 테스트(2~7) · detekt/ktlint(1) · GitHub Actions(1).

**타입 일관성 확인**: `Vault(store, hmac, elapsed, bootCount)` 시그니처가 Task 4 정의와 Task 5·6 테스트 픽스처, Task 12 Koin 정의에서 동일하다. `NhApi(vault, engine)` 도 마찬가지. `Rebalance.plan(balance, targetsBp)` 반환 타입 `Plan(lines, total, cashAfter, targetSumBp)` 을 Task 11이 그대로 쓴다. `Fill(acctNo, name, qty, price, time)` 이 Task 2 정의·Task 6 파싱·Task 11 스낵바에서 일치한다. `PinMode`·`PinFlow(mode, biometric, onDone, modifier, vm)` 가 Task 8 정의와 Task 9·12 호출부에서 일치한다.

**미결 사항**: 없음. 남은 불확실성은 전부 Task 1 Step 8~9(SDK 37 설치)와 Task 13 스모크 체크리스트로 옮겼고, 각각 대안이 적혀 있다.
