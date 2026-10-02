# GameNative AI Dev – MagicPad-prototyp

Utgångspunkt: upstream `utkarshdalal/GameNative`, commit `375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9`.
Fork: https://github.com/stenerstrom/GameNative. Gren: `magicpad-ai-prototype`.
Kontrollerad dokumentation: 2026-10-02. Ingen `AGENTS.md` fanns i denna upstream-version.

## Uppdatering 2026-10-02: installera över befintlig app

**1.2.1-ai-dev.4**, Android `versionCode=24`, är en uppdatering av samma paket `app.gamenative.aidev` med samma signeringscertifikat som ai-dev.1–3. De tidigare APK:erna hade alla versionskod 23. Högre versionskod används nu för varje publicerad version; tidigare lika versionskoder är inte i sig bevis på varför en viss installation misslyckades. Androids regler kräver samma paket, kompatibelt signeringscertifikat och samma eller högre versionskod: [Androids dokumentation om uppdateringar](https://developer.android.com/google/play/app-updates).

Installera APK:n ovanpå **GameNative AI Dev**. Avinstallera inte och rensa inte appdata. Uppdateringsflödet rör bara en ny APK i cache och anropar Androids paketinstallerare; det raderar inte spel, inställningar, backup-filer eller inloggningar. Databas-/speldataformat har inte ändrats i denna uppdatering. Om Android ändå nekar installationen, behåll den befintliga appen och spara den exakta feltexten så att paket/signatur/version kan felsökas.

Efter denna uppdatering: välj **App updates** i AI-vyn eller **Settings → Info → GameNative AI Dev updates**. Funktionen läser forkens publicerade uppdateringsmanifest från GitHub Releases. Tryck **Download update**, därefter **Install update**. Android ber om godkännande. Vid behov öppnas Androids inställning för att tillåta installation från GameNative AI Dev; återvänd sedan och tryck Install update igen. Stäng spel innan installationen. Inga bakgrundsuppdateringar eller tysta installationer görs.

Uppdateringskällan är enbart `stenerstrom/GameNative`, via `releases/latest/download/ai-dev-update.json`. APK:ns URL måste peka på rätt version i denna fork. Hämtningen begränsas till angiven storlek (högst 512 MiB), kontrolleras med SHA-256 och granskas för paketnamn, versionskod/-namn och identiska signerare med den installerade appen. Android verifierar signaturen vid installation. Upstreams gamla uppdateringskontroll är fortsatt avstängd för AI Dev. Ingen ChatGPT-token eller annan appinloggning skickas till GitHub.

Det tidigare signeringscertifikatet är fastlagt i `ai-dev.properties`. Den privata nyckeln har kopierats lokalt till `app/keystores/ai-dev.keystore` med filrättighet 600; sökvägen ignoreras av Git. Nyckeln publiceras inte med release eller källkod. Gradle stoppar om filen saknas eller certifikatet skiljer sig, även vid direkt Gradle-bygge. Bevara en separat privat säkerhetskopia för framtida datorbyten; en nygenererad nyckel kan inte ersätta den för redan installerade appar. Den ursprungliga lokala kopian finns också i `~/.android/debug.keystore` på denna byggdator.

För varje framtida uppdatering: öka `versionCode` och `versionSuffix` i `ai-dev.properties`, uppdatera `docs/ai-dev-release-notes.txt`, bygg med `tools/build-ai-dev.sh`, kör relevanta tester och publicera den verifierade APK:n tillsammans med `build/ai-dev/ai-dev-update.json` i en GitHub-release med taggen `ai-dev-<versionCode>`. Markera releasen som latest för forkens kanal; publicera aldrig en lägre kod än tidigare. Byggskriptet kontrollerar certifikat och versionsmetadata före kopiering till distributionsfilerna. Nyckeln ska inte checkas in eller ersättas med en tillfällig CI-nyckel.

Exempel för denna version efter push av koden:

```sh
gh release create ai-dev-24 \
  build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.4.apk \
  build/ai-dev/ai-dev-update.json \
  --repo stenerstrom/GameNative --target magicpad-ai-prototype \
  --title 'GameNative AI Dev 1.2.1-ai-dev.4' \
  --notes-file docs/ai-dev-release-notes.txt --latest
```

Lokalt verifierat: **106 utvalda tester passerar**, inklusive 8 nya tester av manifest, nedladdning, avbrott, checksumma, versions-/paket-/certifikatkontroller. Testet av Android-manifestet kontrollerar också att uppdateringsaktiviteten inte är exporterad. APK:n bygger och dess signerare matchar tidigare AI Dev-APK:er. Inga uppdateringar har installerats på en fysisk enhet från byggmiljön; bevarad inloggning/speldata över en riktig uppdatering, Androids installationsdialog och OEM-beteende behöver verifieras på MagicPad. Loggar och test-XML finns under `build/ai-dev/verification/update*`.

Aktuell uppdatering: [GitHub-release ai-dev-24](https://github.com/stenerstrom/GameNative/releases/tag/ai-dev-24). APK: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.4.apk`. SHA-256: `f9fb595a9cfc387575e3e1c6490b67a5723b82a835fd9a2aa77baf2390c198f3`.

## Uppdatering 2026-10-02: vanlig chatt utan debug run

Version **1.2.1-ai-dev.3** visar chatt först. Skriv i **Message** och tryck **Send message**. Varken debug run, spellogg eller en läst containerkonfiguration behövs. Assistenten kan svara på vanliga frågor och följdfrågor. Den är fortfarande vår native-assistent som använder ChatGPT-abonnemanget, inte en installerad Codex-process.

Vid öppning av en sparad anslutning med abonnemangstillstånd hämtas modellistan automatiskt. Det gör inget AI-anrop. I tidigare versioner återställdes det tillfälliga modellvalet när vyn öppnades igen, utan ny modellhämtning, vilket kunde lämna Skicka avstängd. Exakt tillstånd på användarens foto av knappen är inte känt. Nu visas ett konkret skäl om Skicka är avstängd; konto- och modellval finns under **Account and model**. Misslyckad kataloghämtning kan provas igen med **Refresh models**.

**Attach settings and log (optional)** är avstängd från början. Välj den för att läsa och granska spelets konfiguration samt en eventuell logg. Konfigurationen går att analysera även utan logg. Bara med en uttryckligen bifogad konfiguration erbjuds modellens begränsade ändringsverktyg. Backup, oförändrad konfigurationshash, användargodkännande och återställning fungerar som tidigare.

De senaste åtta fråge-/svarsparen följer med vid följdfrågor som en begränsad textkonversation i `input`, med `store:false` och `stream:true`. Gamla råa loggbilagor och verktygsanrop spelas inte upp igen. Svar kan dock innehålla uppgifter från tidigare bilagor; **New conversation** rensar det sammanhanget. Byte av konto rensar historiken och stänger av bilagan. Historiken finns bara i vyns minne, överlever rotation men inte att vyn avslutas eller processen stängs. Hemlighetsfiltrering används också för konversationstexten. Inga nya API-nycklar eller betalningsvägar införs.

Prova på MagicPad: uppdatera över AI Dev utan att avinstallera, öppna assistenten med befintlig anslutning, skriv ”Hej, vad kan du hjälpa mig med?” utan bilaga och ställ sedan en följdfråga. Prova därefter att bifoga konfiguration utan logg. Chattändringen är inte ännu testad med ett riktigt AI-anrop på enhet; det verifierade anropet nedan gjordes med föregående version.

Verifierat lokalt: **98 tester passerar, 0 fel, 0 hoppade över**, varav 14 nya tester för vanlig chatt, begränsad historik, filtrering, automatisk modellhämtning vid återöppning, opt-in-bilaga utan logg, katalogfel/omförsök, kontobyte, avbrutet/misslyckat anrop och avvisning av oombedda konfigurationsförslag utan bilaga. ViewModel-testerna körs i Robolectric med en testprovider utan verkliga AI-anrop. Tidigare tester av strömmar, OAuth och backup/restore passerar också. APK:n bygger, signaturen verifieras och paket/signeringscertifikat matchar tidigare AI Dev. Den nya layouten behöver fortfarande provas på MagicPad.

APK: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.3.apk`, även som `build/ai-dev/GameNative-AI-Dev.apk`. SHA-256: `367563a1f8de5e3aad6f5e45796340260fae4e06dc9f305b843f5358184f3457`. Bygg-/testloggar: `build/ai-dev/verification/chat-build-tests.log`, `chat-final-build.log` och `chat/TEST-*.xml`.

## Uppdatering 2026-10-02: modellista och svarsläsning

Version **1.2.1-ai-dev.2** kan installeras som uppdatering över den första AI Dev-APK:n. Samma paket och signeringsnyckel används; avinstallera inte appen eller rensa dess data för uppdateringen. Versionsnumret visas högst upp i AI-vyn.

Modellerna hämtas från `GET https://api.openai.com/v1/models` med den valda anslutningens token. Endast poster med `visibility: list` visas, i serverns ordning. Namnet hämtas från `display_name`, medan `slug` skickas vid inferens. Det finns ingen inbyggd modellista. Refresh models begär en ny nätverkshämtning och visar tidpunkt och valt modell-ID. Katalogen behöver inte vara samma som listan i Codex. Att en modell saknas är inte i sig bevis på att kontot saknar åtkomst till modellen i andra produkter; orsaken kräver server-/kontoinformation som denna miljö inte har.

Enhetsbilden visade GPT-6 Astra och äldre modeller samt felet `The completed response was empty`. Den gamla parsern ignorerade all text före `response.completed`. Nya parsern behåller text-delta, färdig text och färdiga output-items, och använder dem när sluthändelsen inte upprepar output. En fylld terminal output har företräde och dubbleras inte. Verktygsargument från delhändelser blir aldrig ändringsförslag; det krävs ett helt färdigt item samt `response.completed`. Avbrott, failed och incomplete avvisas även om text redan har kommit. Verkligt tomma svar ger ett fel med antal händelser/delar och request-/response-ID, utan råtext eller credentials.

Regressionsfallet är återskapat lokalt med syntetiska SSE-händelser. Det bevisar en brist i den gamla parsern, men råströmmen från surfplattan har inte inspekterats. Efter uppdateringen visar användarens foto från MagicPad ett lyckat **Verify AI access** med modell-ID `gpt-6-astra`: `Completed AI response: ChatGPT plan connection verified.` Detta verifierar ett riktigt avslutat anrop för den anslutningen och modellen. Modellkatalogen i sig bevisar inte lyckad inferens.

Kvarstående användbarhetsproblem: samtyckesfliken kan visa en snurrande indikator även när det går att återvända till appen och använda anslutningen. Användaren har kunnat återvända med Androids Tillbaka. Orsaken till webbläsarens beteende är inte fastställd; den ska inte beskrivas som ett verifierat fel i OpenAI:s server eller som nekad kontobehörighet. Automatisk retur/stängning och tydligare inloggningsstatus återstår att förbättra. Ett lyckat verifieringsanrop bevisar inte att hela inloggningsupplevelsen fungerar utan problem.

Verifierat för denna uppdatering: den nya regressionssviten gav 6 fel av 12 tester före rättningen; efter rättningen passerar alla **84** utvalda tester (40 assistenttester och 44 befintliga FPS-/containertester). APK:n bygger, APK-signaturen verifieras och certifikatets SHA-256 är samma som i första APK:n. Paketet är fortfarande `app.gamenative.aidev`. Inga riktiga modell-anrop har gjorts från byggmiljön. Bygglogg och test-XML finns lokalt i `build/ai-dev/verification/stream-fix-build-tests.log` respektive `build/ai-dev/verification/stream-fix/`.

APK för svarsläsningsrättningen: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.2.apk`. SHA-256: `c31b9993a6cf35022aee0154554677446af0599b8d6b583cb591c8fd46c8a052`. Den vanliga sökvägen `build/ai-dev/GameNative-AI-Dev.apk` innehåller det senaste bygget.

## Vad som implementeras

En Kotlin-assistent i Android-appen för ett valt, installerat spel. Egen provider använder officiell **Sign in with ChatGPT** och `POST https://api.openai.com/v1/responses` direkt. Ingen Codex-binär, separat dator/server, importerad Codex-token eller API-nyckel behövs vid användning. Inloggningssteget öppnar OpenAI i en Android Custom Tab/systemwebbläsare; användaren återvänder sedan till appen. Fråga, diagnostik, svar, godkännande och återställning finns i appen.

Assistenten stöder vanlig textchatt med följdfrågor och valfri konfigurations-/logganalys med ett förslag per anrop, utan autonom agentloop. När inställningar bifogas kan modellen föreslå befintlig FPS-begränsning och en liten tillåten uppsättning containerupplösningar. Appen sköter läsning, validering, tillämpning och återställning. Modellen kan inte starta spel, köra shell, ändra godtyckliga filer eller skriva inställningar på egen hand.

Det finns också **Local 30 FPS test proposal (no AI)**. Detta är ett uttryckligen lokalt provförslag som testar ändringsflödet utan konto eller nätverk, och är inte ett AI-svar.

## OpenAI-inloggning: verifierat stöd och kvarstående verifiering

De aktuella officiella dokumenten beskriver abonnemangsanvändning för öppna och lokalt körda projekt, med behöriga Plus-/Pro-konton. Kontoinloggning (`openid profile email`) och rätt att göra modell-anrop (`resource.invoke chatgpt.tokens.use.direct`, tillsammans med `offline_access`) är skilda saker.

Den valda vägen är native OAuth + Responses, inte Codex app-server. OpenAI beskriver app-server som ett valfritt sätt att använda samma auktoriserade OAuth-token. De lästa sidorna etablerar inte något färdigt Android-SDK eller stöd för att paketera och köra Codex på Android. Vår implementation av loopback-flödet är därför en Android-prototyp av det dokumenterade protokollet och kräver enhetstest.

- Stabil slumpmässig `ext_agent_host_id`, UUID per appinstallation.
- Dynamisk första registrering med `dynamic_agent_client`; utfärdat klient-ID sparas före kodväxling och återanvänds vid återinloggning.
- Separata anslutningar/konton/workspaces, även när e-postadressen är samma.
- Ny `state`, OIDC `nonce` och PKCE S256 per försök.
- Retur enbart till `http://127.0.0.1:<ledig-port>/auth/callback`. Lyssnaren startas före webbläsaren och stängs vid avslut eller timeout. Ingen egen påhittad OAuth-returadress används.
- RS256-signatur mot OpenAI JWKS, issuer, audience, azp vid flera audiences, expiry, nonce och subject verifieras.
- AES-GCM-krypterade credentials med Android Keystore, atomisk lagring i `noBackupFilesDir`. Inga tokens till modell, loggar, Git, browser storage eller andra appar.
- Serialiserad tokenförnyelse, roterade tokens sparas tillsammans. Utloggning försöker återkalla refresh-token och rensar lokalt; misslyckad fjärråterkallelse visas.
- Modellval hämtas från den aktuella anslutningens `/v1/models`. Listan bevisar inte behörighet.
- **Verify AI access** gör ett litet faktiskt modell-anrop. Endast ett icke-tomt svar med `response.completed` räknas som verifierat. `response.failed`, ofullständiga eller avbrutna strömmar räknas inte.
- `store:false`, `stream:true`, array-formad input, instructions och namespacat function-verktyg. Ingen `temperature`, `max_output_tokens`, `previous_response_id`, hosted MCP eller tool search.
- Ingen separat API-debitering och inget automatiskt byte av betalningsväg. Användaren styr appens abonnemangsandel och eventuella credits i ChatGPT → Settings → Usage.

**Ett verkligt AI-svar är verifierat på MagicPad genom användarens enhetstest 2026-10-02.** Fotot visar modell-ID `gpt-6-astra` och `Completed AI response: ChatGPT plan connection verified.` Den uppdaterade parsern kräver både ett icke-tomt svar och `response.completed` för denna status. Katalogens hämtningstid på fotot är 2 oktober 2026 kl. 08:53:52; det är katalogens tid, inte en exakt tidpunkt för inferensen. Ingen fysisk enhet är ansluten till byggmiljön och inga credentials eller råa OAuth-/AI-svar har hämtats därifrån. Testet verifierar denna anslutning/modell vid försöket, inte andra modeller eller framtida anrop.

Vanliga konkreta hinder:

| Resultat | Betydelse/åtgärd |
| --- | --- |
| Identitet giltig men direct-scope saknas | Abonnemangstillstånd saknas. AI avstängd; välj Continue with ChatGPT för uttryckligt nytt samtycke. |
| `subscription_sharing_user_not_eligible` | Konto/workspace/policy är inte berättigat. Ingen automatisk OAuth- eller anropsloop. |
| `subscription_sharing_usage_limit_exceeded` | Gå till Manage usage; appgräns kan vara nådd. |
| 403 med `detail` | Exempelvis region eller policy; appen visar serverns feltext. |
| `subscription_sharing_unsupported_capability` | Kontrollera request-parametern som servern anger. |
| 503/direct routing unavailable | Försök senare; credentials bevaras. Prototypen gör inga automatiska omförsök. |
| OAuth-timeout/bakgrundsprocess dödad | Återgå till appen och börja ett nytt försök. Pending PKCE-material sparas inte vid processdöd. |

Källor:

- [Översikt](https://developers.openai.com/siwc/token-sharing-open-source)
- [Registrering och inloggning](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Konton, förnyelse och återkallelse](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Modeller och avslutad inferens](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Codex app-server](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server)
- [Preview-begränsningar](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Felkoder](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [OIDC-signaturverifiering](https://developers.openai.com/siwc/website)
- [Namespacade function tools](https://developers.openai.com/api/docs/guides/function-calling)

## Återanvända delar av GameNative

| Befintlig del | Användning i prototypen |
| --- | --- |
| `BaseAppScreen` och `GameOptionsPanel` | Ny meny för samtliga spelkällor: **AI assistant · ChatGPT**. |
| `ContainerUtils.getContainer`, `ContainerManager`, `.container` | Läser samma konfiguration som nästa spelstart använder. Ingen extra konfigurationsdatabas. |
| `XServerScreen`, `DebugReportUtils` | Läser spelets `debug_run_<appId>.log` eller det senaste kvarvarande komprimerade felsökningsprotokollet med matchande app-ID. |
| `DiagnosticsLog` | Alternativ spelbunden wrapper-logg. Gemensam `wine_debug.log` används inte eftersom spelets identitet är osäker. |
| `PerfSampler`, `perf.json` | Numeriska värden ur matchande rapport, de sista 20 samplen inklusive FPS, frametid P50/P99/max när de finns, CPU/GPU/temperatur. |
| Session metadata | Senaste genomsnittliga FPS och sessionslängd, uttryckligen märkta som historiska värden. |
| `PerformanceMetricsCollector`/`JsonlSessionLog` | Kartlagda men inte använda som spelbevis: dessa sessionsfiler saknar säker app-ID-bindning i nuvarande format. |
| `fpsLimiterEnabled`/`fpsLimiterTarget` i `extraData` | Samma FPS-gräns som befintlig snabbmeny/renderer använder. |
| `ModDiagnosticSanitizer` | Grundfilter för sökvägar, URL-frågor och hemligheter, kompletterat med OAuth-token, lösenord, cookies, JWT, e-post och privata nycklar. |
| Befintlig AI debug run | Samlar loggar + PerfSampler-data och erbjuder rapport till GameNatives Discord-relay. Den är inte en lokal ChatGPT-provider. Rapporten kan behållas lokalt och läsas av assistenten. |

Konfigurationen som skickas är en allowlist: upplösning, grafik-/översättningskomponenter och relevanta FPS-inställningar. Hela `envVars`, startargument, enhetsserienummer, kontodatabaser och logcat skickas inte. Storleksgränser gäller för rålogg, komprimerad logg, rapport och modellström. Komplett begränsad logg filtreras före avkortning. Användaren ser och kan redigera diagnostiken före sändning. Filtrering kan inte identifiera varje tänkbar hemlighet.

`GameAssistantTools` binder alla läsningar och skrivningar till app-ID:t från Android-skärmen. Ingen modell får välja filvägar. Förslag valideras oberoende av modellen. Tillämpning kräver användarens knapptryck, stoppad spel/container-session och oförändrad konfigurationshash. Backup skrivs atomiskt före konfigurationen; ett misslyckat backup-skrivförsök stoppar ändringen. Bara ett utestående experiment per spel tillåts.

Backup i `noBackupFilesDir/assistant/undo/<appId>.json` innehåller original och förväntat efterläge. Återställning skriver bara berörda fält och bevarar andra ändringar. Om ett berört fält ändrats manuellt till ett tredje värde blockeras återställningen med backup kvar. Den överlever processomstart. Den raderas vid avinstallation/rensning av appdata. Detta är inte en backup av sparfiler, Wine-registret eller spelinstallationen.

## Separat app och paketgranskning

Flaggan `-PaiDev=true` aktiverar funktionen endast i debug-bygget:

- Appnamn: **GameNative AI Dev**.
- Paket-ID: **`app.gamenative.aidev`**.
- Version: upstream-version med suffix och egen stigande versionskod från `ai-dev.properties` (nu `-ai-dev.4`, kod 24).
- Kodnamespace förblir `app.gamenative`, så upstream/JNI-namn och klassreferenser behålls.
- `FileProvider` och AndroidX Startup får unika authorities från applicationId.
- Launcher-aliasarnas klassnamn fortsätter vara `app.gamenative.MainActivityAlias…`, men deras komponentpaket är utvecklingsappens; namn och label kontrolleras i sammanfogat manifest.
- Startlänkar använder `gamenative-aidev://run`, start-intent `${applicationId}.LAUNCH_GAME`, egna home-/Nexus-/Discord-/nxm-scheman. Genvägar pekar uttryckligen på rätt paket.
- Nexus OAuth och Discord-relay-inloggning är avstängda i AI Dev eftersom deras fasta upstream-returadresser/registreringar inte tillhör forken. De kräver egna registreringar innan de kan aktiveras. ChatGPT använder egen officiell dynamisk registrering och loopback, så detta blockerar inte ChatGPT.
- Steam, GOG, Epic, Amazon, EA och Rockstar-flöden har inte bytts till forkens egna OAuth-klienter; befintliga SDK-/WebView-/interna returer är kvar. Deras verkliga inloggningar måste provas på enhet.
- Hårdkodade privata sökvägar för DXVK-cache, E:-enhet, gamepad-filer och mediaomvandling är rättade till rätt app-/imagefs-sökväg. `EVSHIM_BASE_PATH` sätts för den medföljande binären som annars har ett fallback till originalpaketet.
- Originalets automatiska uppdateringskontroll är avstängd i AI Dev.

Den nya appen har separat data och separata spelinstallationer/inställningar. Den läser inte originalappens privata filer. Importera konfiguration med befintliga funktioner om det behövs, och kontrollera importerade absoluta sökvägar. `LICENSE`, `THIRD_PARTY_NOTICES` och befintliga upphovsrättsnotiser är bevarade. Inga native-bibliotek har byggts om; upstreams inkluderade arm64-bibliotek används.

## Bygga

Krav: JDK 17, Android SDK platform 36, build-tools 35.0.0 och internet för Gradle-beroenden. Projektets Gradle Wrapper är 8.12.1; AGP är 8.8.0. Upstream har en varning om compileSdk 36 med denna AGP-version, men baslinjen byggde. SteamGridDB och PostHog-nycklar behövs inte för prototypen. Skapa `local.properties` även om du saknar sådana nycklar, eftersom upstreams secrets-plugin kräver filen:

```properties
sdk.dir=/absolut/sökväg/till/Android/sdk
```

Bygg i Android Studio med JDK 17 eller i terminal:

```sh
./tools/build-ai-dev.sh
```

Skriptet kör `./gradlew :app:assembleModernDebug -PaiDev=true`. APK:n verifieras och kopieras till `build/ai-dev/GameNative-AI-Dev.apk` samt ett versionsmärkt filnamn. Python 3 behövs för paketeringen. Återställ den ursprungliga privata nyckeln i `app/keystores/ai-dev.keystore` innan byggning på en annan dator. Saknad eller ändrad nyckel stoppar bygget. Installera bara AI Dev-APK:n om originalet ska vara kvar.

Relevanta tester:

```sh
./gradlew :app:testModernDebugUnitTest -PaiDev=true \
  --tests 'app.gamenative.assistant.*' \
  --tests 'app.gamenative.ui.component.FpsLimiterUtilsTest' \
  --tests 'app.gamenative.ui.component.dialog.ContainerConfigDialogContainerUpdateTest'
```

I denna session hämtades verktygen lokalt till `/tmp/gamenative-toolchain`. För att återanvända dem medan de finns kvar:

```sh
export JAVA_HOME=/tmp/gamenative-toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/tmp/gamenative-toolchain/android-sdk
export GRADLE_USER_HOME=/tmp/gamenative-toolchain/gradle
./tools/build-ai-dev.sh
```

`/tmp` är tillfälligt; använd Android Studios SDK/JDK för en beständig utvecklingsmiljö. `local.properties`, APK:er och byggcacher ignoreras av Git. Byggskriptet ändrar inte din globala Java-/SDK-installation.

## Installera och testa på Honor MagicPad

1. För över `GameNative-AI-Dev.apk` till surfplattan och öppna den i filhanteraren. Tillåt installation från den valda appen. Alternativt med USB-felsökning: `adb install -r build/ai-dev/GameNative-AI-Dev.apk`.
2. Kontrollera att både originalet och **GameNative AI Dev** finns kvar. Starta AI Dev och slutför GameNatives vanliga hämtning/installation av körmiljön.
3. Logga in i önskad spelbutik eller lägg till ett eget spel. Starta spelet en gång eller skapa dess container via **Edit container**. Ingen del av prototypen antar vilka grafikdrivrutiner/SoC-egenskaper MagicPad har.
4. Valfritt för logganalys: välj **AI debug run**, reproducera ett kort avsnitt och avsluta spelet. Stäng rapportdialogen utan att skicka/radera rapporten. Alternativt **Play with diagnostics** för wrapper-logg. Detta behövs inte för vanlig chatt eller konfigurationsanalys.
5. Öppna spelets meny → **AI assistant · ChatGPT**. För vanlig chatt går du direkt till **Message**. För konfigurationsanalys väljer du **Attach settings and log (optional)** och granskar innehållet. Avsaknad av logg/prestandarapport visas uttryckligen.
6. För ett lokalt ändringsprov: **Review optional diagnostics → Read configuration and game log → Local 30 FPS test proposal (no AI)** → granska → **Back up and apply these changes**. Kontrollera nästa spelstarts FPS-gräns i snabbmenyn. Stäng appen, öppna den igen och välj **Restore previous settings**. Bekräfta att tidigare FPS-inställning återkommit. Prova också att ändra ett annat fält och att det bevaras vid restore.
7. Välj **Continue with ChatGPT** om du inte redan har en ansluten profil. Granska OpenAI:s officiella samtycke på surfplattan. Om fliken ligger kvar, återvänd med Androids Tillbaka och kontrollera appens status. En kvarliggande flik bevisar inte att inloggningen är klar. När appen visar anslutningen väljer du modell och **Verify AI access**; befintlig anslutning behöver inte registreras på nytt för varje test. Spara bara felkod/request-ID vid fel, aldrig tokens eller hela OAuth-returadressen.
8. Ett avslutat textsvar från knappen är beviset på att just den anslutningen/modellen fungerade. Ett konto i listan eller en modellista är inte samma bevis.
9. Skriv till exempel ”Det här spelet hackar, hjälp mig att få stabila 30 FPS.” Tryck **Send message** för att börja prata om problemet. För ett tillämpbart förslag bifogar du inställningarna, redigerar bort känslig diagnostik och trycker **Send with reviewed settings**. Granska förklaring, berörda inställningar och osäkerhet innan du tillämpar.
10. Prova avböjt samtycke, offline-läge, avbruten inloggning, rotation och appomstart. Prova Sign out och återinloggning i samma anslutning samt en separat workspace-anslutning. Vid nekad abonnemangsåtkomst fungerar det lokala ändrings-/återställningsflödet fortfarande.

Vid återställningskonflikt: backup behålls; sätt det berörda fältet manuellt till experimentets värde eller originalvärdet och försök igen. Återställ inte en godtycklig gammal helkonfiguration över nyare inställningar. Om originalet redan var 30 FPS är ett lokalt 30 FPS-prov en no-op och avvisas utan backup.

För jämförbara mätningar: samma spelversion, sparpunkt/scen, längd, ljusstyrka, skärmuppdateringsfrekvens, energiläge, laddningsstatus och ungefärlig starttemperatur. Kör samma logg-/mätmetod både före och efter och upprepa flera gånger. Jämför FPS och frametider, inte bara en enskild medelsiffra. Loggning kan påverka prestanda. Sänkning av upplösning kan minska GPU-belastning men behöver inte ändra spelets egen upplösning; FPS-cap kan inte skapa saknade bildrutor. Ingen prestandaförbättring på MagicPad är uppmätt i denna session.

## Underhåll

```sh
git fetch upstream
git switch magicpad-ai-prototype
git rebase upstream/master
./tools/build-ai-dev.sh
```

Håll integrationen i `app/gamenative/assistant/`. De små övriga ändringarna gäller meny, byggflagga och paketoberoende sökvägar. Ändra inte namespace eller JNI-symboler bara för att applicationId har ett suffix. Upstreams ordinarie build utan `-PaiDev=true` behåller originalidentiteten och visar inte assistenten.

## Verifierat vid första bygget 2026-10-01

| Kontroll | Resultat |
| --- | --- |
| Oförändrad upstream, `:app:assembleModernDebug` | Godkänd före källkodsändringarna, efter installation av lokal JDK/SDK och skapad `local.properties`. |
| Slutlig AI Dev APK | Byggd med JDK 17 / SDK 36 / Gradle Wrapper. |
| Relevanta JVM-/Robolectric-tester | **72 passerade, 0 misslyckade, 0 hoppade över**: 28 assistenttester, 26 befintliga FPS-tester och 18 befintliga containeruppdateringstester. |
| OAuth | Lokala tester av signerade och förfalskade ID-tokens, issuer/audience/nonce/expiry/azp, loopback-state/path/dubblettparametrar, identitet utan AI-scope och tokenrotation. |
| Modellprotokoll | Namespacade verktyg, validering av förslag, avbruten ström, sent abonnemangsfel och krav på completed-event testade med syntetiska svar. |
| Konfiguration/logg | Robolectric testar GameNatives riktiga containerläsare, spelbunden logg, filtrering, tillämpning, återöppning och återställning; ingen logg från annat spel används. |
| Återställning | Testat: hashkonflikt, manuell ändring i berört fält, andra fält bevaras, saknade fält, simulerat avbrott efter backup, skydd mot överskriven backup samt misslyckad backup utan configändring. |
| Appidentitet | AAPT och sammanfogat manifest: `app.gamenative.aidev`, `GameNative AI Dev`, arm64-v8a, minSdk 29, targetSdk 36. Robolectric bekräftar aktiverad launcher, separat provider och icke-exporterad assistentaktivitet. |
| APK-signering | `apksigner verify --verbose`: godkänd APK Signature Scheme v2, en signerare (debug-build). |
| Licens och diff | `LICENSE` och `THIRD_PARTY_NOTICES` oförändrade; `git diff --check` godkänd. |
| Ansluten Android-enhet | `adb devices -l`: ingen ansluten. |
| Verklig OAuth/AI-inferens, Keystore på fysisk enhet, UI-layout, installation bredvid originalet, butikernas inloggningar, native spelkörning och FPS-förbättring | **Inte testat på enhet**. Kräver MagicPad-teststegen ovan. Ingen lyckad kontobehörighet eller prestandavinst påstås. |

Den första APK:ns SHA-256 (den aktuella finns i `build/ai-dev/SHA256SUMS`):

```text
f212fdacff54b6bc8212778296639818a4c9490dee9444cba66c669906bb522c
```

Lokala bygg-/testloggar och XML-resultat sparas i `build/ai-dev/verification/` (ignoreras av Git). Robolectric gav vid sista körningen även en varning när en temporär katalog skulle städas; testresultaten och Gradle-byggstatus var godkända. Den fulla upstream-testsviten och Android instrumentation-tester på fysisk enhet har inte körts.

## Nästa milstolpe

Efter det lyckade AI-verifieringsanropet återstår fysisk verifiering av spelanalys med logg, ett validerat förslag, tillämpning och återställning samt återanvändning av anslutningen efter appomstart/tokenförnyelse. Inloggningsflikens retur och status behöver förbättras. Därefter bättre spel- och konfigurationsbindning av mätningar, jämförbara före/efter-rapporter, fler validerade inställningar och eventuellt upprepade optimeringstester. Generaliserad agentloop, godtyckliga filändringar och automatiska testkörningar ingår inte här.
