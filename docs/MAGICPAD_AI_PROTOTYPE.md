# GameNative AI Dev – MagicPad-prototyp

Utgångspunkt: upstream `utkarshdalal/GameNative`, commit `375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9`.
Fork: https://github.com/stenerstrom/GameNative. Gren: `magicpad-ai-prototype`.
Kontrollerad dokumentation: 2026-10-02. Ingen `AGENTS.md` fanns i denna upstream-version.

## Uppdatering 2026-10-02: reparation av kontrollbryggan

**1.2.1-ai-dev.11**, Android `versionCode=31`, rättar två verifierbara fel efter rapporten att kontroller inte fungerar i något spel.

### Orsak och ändring

Paketbytet i den första AI Dev-versionen satte `EVSHIM_BASE_PATH` i Wine-processens miljö, men inte i Android-processens miljö. `libevshim.so` läser variabeln i sin native-konstruktor när `WinHandler` laddar biblioteket. Utan värdet används `/data/data/app.gamenative/files`, som hör till originalappen. Java skriver samtidigt till AI Devs `filesDir/gamepad_shm`. Native-väckningen kan då sakna sin mappning, och sekvensräknaren i rätt fil uppdateras aldrig. Wine-sidans `vjoy_updater` väntar på just den sekvensräknaren. Detta kan bryta både fysisk och virtuell gamepad-input även om kontrolltestet registrerar Android-händelser och Java-skrivningar. Den medföljande binären innehåller samma miljövariabel, fallback och JNI-felmeddelande som källkoden.

`PluviaApp.attachBaseContext` konfigurerar nu värdprocessens sökväg före content providers, DI och `onCreate`. Wine får samma sökväg efter att övriga miljöinställningar slagits ihop. Ingen ny native-binär krävs. Felet är äldre än kontrolltestet; att det är den enda orsaken till användarens enhetsproblem är inte verifierat utan enhetstest.

Återanslutningens `finally` slog tidigare upp spelvyn igen och kunde hoppa över städningen när vyn inte längre fanns. Nu städas den fångade bryggan alltid. Dess inputspärr släpps även om vyn försvinner, men filskrivning/ny anslutningsstatus sker bara om exakt samma `WinHandler` fortfarande är aktiv. Spelstart och spelstopp återställer också spärren.

Vid spelstart verifierar `WinHandler` att JNI-väckning ändrar sekvensräknaren i varje Java-mappad kontrollfil. `inspect_controllers.runtimeBridge.buffers[].nativeWakeReady` är `null` före kontroll, `false` när kontrollen misslyckats och `true` när samma buffert reagerar. Det är fortfarande inget kvitto från PC-spelet. Vibrationspollaren startas inte för en trasig native-mappning: native-funktionen returnerar omedelbart i det fallet och kunde annars orsaka en tät CPU-loop. Ingen CPU-förbättring har mätts på enheten.

### Installation och enhetstest

1. Avsluta spelet. Uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.11-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-31/GameNative-AI-Dev-1.2.1-ai-dev.11.apk) ovanpå AI Dev. Samma paket och signerare används. Avinstallera inte och rensa inte appdata.
2. Öppna appen på nytt. Native-bibliotekets sökväg läses vid processstart, så en redan laddad bibliotekskopia kan inte rättas genom att enbart stänga chattpanelen.
3. Starta ett spel med dess befintliga inställningar. Testa först fysisk handkontroll och skärmens gamepad-knappar utan nytt liveförsök eller API-byte.
4. Om input fortfarande saknas, öppna **Kontrolltest**, starta 20-sekunderstestet och prova i spelet. Be därefter assistenten läsa både kontrolltestet och `nativeWakeReady`. Rapportera vilka steg som får signaler och om spelet reagerar. Ingen debug run behövs.
5. Prova att öppna/stänga chatten och kontrolltestet, avbryta återanslutning och starta nästa spel. Input ska inte förbli spärrad efter dessa åtgärder. Sparade spelinställningar, kontrollprofiler och befintliga säkerhetskopior ska vara orörda.

### Verifiering

Regressionstesterna för appstart och försvunnen/bytt spelvy fallerade före rättningen. Appstartstestet kör verklig `Application.attach` och `PluviaApp.attachBaseContext`, med Androids libc-miljöanrop ersatta av en testsimulering eftersom Robolectric inte implementerar dem. Bryggtesterna kör verklig Java-minnesmappning, API-svar och kontrollmappning; JNI-väckningen ersätts med en testgräns. De skiljer en lyckad Java-skrivning från utebliven native-sekvensändring, kontrollerar att trasiga mappningar inte startar vibrationspollare och att en gammal återanslutning inte ändrar en ny spelomgångs buffert. UI-testerna kör liveförsök, ångra och återanslutning med befintlig säkerhetskopia.

**223 tester godkända, inga fel eller överhoppade tester**, och `assembleModernDebug` lyckades. Paket-ID, versionskod och tidigare signerare är kontrollerade; alla 32 paketerade native-bibliotek är byteidentiska med ai-dev.10. APK SHA-256: `5eb9afa9c7e8d00a0cd2f41c7d2a2c6d964b3dad6b661edad4f38ac31a51d6ff`.

Verifieringsfiler: `build/ai-dev/verification/controller-recovery/`, inklusive felande tester före rättningen. Verklig Bluetooth/USB, JNI, SDL/Wine och PC-spelens respons kräver MagicPad. Ingen fysisk Android-enhet var ansluten. Ingen fungerande kontrollrespons på enheten påstås utifrån JVM-testerna.

## Uppdatering 2026-10-02: kontrollförsök utan omstart

**1.2.1-ai-dev.10**, Android `versionCode=30`, ersätter den generella spärren med separata liveförsök för kontrollbryggan. Den tidigare sparade konfigurationens säkerhetskopia kan finnas kvar samtidigt. Permanent konfigurations-, fil- och modskrivning kräver fortfarande stoppat spel.

### Varför vissa XInput-val behöver omstart

GameNative använder `inputType` på flera ställen. `setupXEnvironment` skickar bland annat `SDL_XINPUT_ENABLED`, `SDL_DIRECTINPUT_ENABLED` och `SDL_JOYSTICK_HIDAPI` till den nya spelprocessen. Att ändra en Java-egenskap i appen efteråt skriver inte om den redan startade processens miljö eller kontroller som den redan öppnat. Steam Inputs startkonfiguration och laddade komponenter har liknande begränsningar.

`WinHandler` har samtidigt API- och mapperinställningar som läses när dess äldre UDP-protokoll får `GET_GAMEPAD`. Dessa kan ändras i minnet under körning. **Prova bryggan live** använder denna möjlighet, men gör inget anspråk på att ändra SDL eller få effekt i ett spel som inte frågar den bryggan igen. `inspect_controllers` visar aktuell bryggstatus, antal äldre upptäcktsförfrågningar och den separata liveåterställningen. En bryggskrivning eller ändrad egenskap är fortfarande inget kvitto från PC-spelet.

### Prova på MagicPad

1. Stäng spelet och uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.10-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-30/GameNative-AI-Dev-1.2.1-ai-dev.10.apk) ovanpå AI Dev. Samma paket och signerare; avinstallera inte.
2. Starta Dark Souls och välj **Quick Menu → Codex i spelet → Kontrolltest → Återanslut kontrollbryggan**. Den kopplas virtuellt från i ungefär 350 ms och återansluts. Spelet fortsätter; ingen AI-fråga eller sparad inställningsändring görs. Stäng panelen och prova kontrollen. Spelet/gästen behöver stödja återanslutning.
3. Med spelåtkomst på, skriv exempelvis: ”Undersök aktuell kontrollbrygga och föreslå ett separat liveförsök med XInput. Behåll min sparade ångrapunkt.” Ett förslag med **bara** kontroll-API och/eller DirectInput-mappare får en **Prova bryggan live**-knapp om den aktiva bryggan har ett annat läge. Blandade förslag med SDL, Steam Input eller andra inställningar måste delas upp; bara att spara deras JSON vore ingen fungerande liveändring.
4. Tryck **Prova bryggan live**, stäng panelen och testa samma knappar. **Ångra liveförsök** i panelens överkant återställer bryggans tidigare läge direkt. Befintlig sparad säkerhetskopia och konfigurationsfil ska vara identiska före/efter. Flera liveförsök behåller det ursprungliga läget som återställningspunkt.
5. Gör ett nytt 20-sekunders kontrolltest för nytt underlag. Om spelet använder SDL-startvalen eller håller kvar en redan öppnad kontroll kan en spelomstart fortfarande behövas. Ändra inte fler startinställningar på chans mellan varje prov. Liveförsöket sparas inte och försvinner när spelomgången avslutas; ett lyckat försök kan senare sparas via ett nytt granskat förslag med spelet stoppat.

### Rättning och återställning

Den äldre `GET_GAMEPAD`-grenen hade lägen som inte motsvarade enum-namnen: `XINPUT` kunde neka XInput och `BOTH` nekade DirectInput. Besluten är nu explicita: `XINPUT` tillåter XInput, `DINPUT` tillåter DirectInput, `BOTH` tillåter båda och `AUTO` undviker dubbel exponering efter att samma process frågat efter XInput. Processhistoriken töms vid byte av API. SDL-koden ändras inte av denna rättning. Det är inte känt om detta var orsaken till Dark Souls-problemet på användarens enhet.

Liveförsök använder före/efter-värden och launch-token. Föråldrade förslag och en annan spelomgång avvisas. Ångra skriver inte över ett läge som ändrats utanför assistenten. Ett delvis misslyckat skrivförsök försöker återställa föregående läge. Ingen fil eller beständig konfigurationsbackup berörs. Återanslutning sänker buffertens anslutningsflagga, hindrar inmatningsskrivningar från att återansluta för tidigt och uppdaterar aktuell anslutning igen även om användaren avbryter åtgärden; en ny spelomgång berörs inte av den gamla åtgärdens städning.

### Verifiering

**219 tester godkända, inga fel eller överhoppade tester.** Nya tester kör riktiga `WinHandler.handleRequest`-svar för samtliga API-lägen och ett lägesbyte på samma handler, inklusive mapperbytet. Minnesfiltestet verifierar frånkoppling, spärrad förtida återskrivning och neutral återansluten kontrollstatus. Transaktionstester täcker direkt ångra, flera försök, blandade/otillåtna inställningar, annan spelomgång, manuell ändring, delvis fel och avbruten återanslutning. Compose-tester i både liggande och stående läge kör agentförslag → liveförsök → liveångra → återanslutning, med oförändrade konfigurations- och backupbytes och utan fler AI-anrop vid lokala åtgärder. Befintliga tester för assistent, kontroller och konfiguration körs också. Byggkommando och miljö är samma som i avsnittet för ai-dev.9 nedan; paketeringsskriptet kontrollerar versionskod och signatur.

Tester med JNI-shadow ersätter native-laddning/väckning; den riktiga Wine-gästen och en fysisk kontroll körs inte i testmiljön. Modellsvar är simulerade. Test på MagicPad återstår för virtuell hotplug, återanslutning i Dark Souls, faktisk användning av äldre UDP kontra SDL, korrekt knapprespons, in-place-uppdatering och live-modellanalys. Lokal verifiering sparas i `build/ai-dev/verification/live-controller-changes/`.

## Uppdatering 2026-10-02: kontrolltest under spel

**1.2.1-ai-dev.9**, Android `versionCode=29`, ger assistenten underlag för att felsöka att ingen input når spelet. Kontrollen testas under vanlig spelkörning; inget debug run krävs. Detta löser inte i sig ett ännu okänt fel på den fysiska enheten.

### Prova på MagicPad

1. Stäng spelet. Välj **⋮ → Appuppdateringar**, eller installera [ai-dev.9-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-29/GameNative-AI-Dev-1.2.1-ai-dev.9.apk) ovanpå AI Dev. Samma paket-ID och signerare används. Avinstallera inte och rensa inte appdata.
2. Starta det berörda spelet normalt. Välj **Quick Menu → Codex i spelet → Kontrolltest → Starta kontrolltest · 20 s**. Chatten stängs. Tryck och släpp A/B och styrkorset, rör båda spakarna i alla riktningar och tryck in/släpp båda triggers. Kontrollera om spelet reagerar.
3. Rutan visar Android-prover och skrivningar till Wine-bryggan. Efter 20 sekunder slutar insamlingen; den öppnar inte chatten automatiskt. Tryck **Granska testet → Analysera testet**. Spelåtkomst behöver vara aktiverad. Analysknappen skickar en fråga om utebliven input genom den befintliga ChatGPT-anslutningen och visar svaret i chatten.
4. Assistenten kan läsa testet, spelarplatser, profilmappningar, kontroll-API och aktuell konfiguration. Bedömningen ska skilja Android-mottagning, profilens valda utdata, saknad brygga och genomförd bryggskrivning. Tala om ifall spelet reagerade; appen kan inte själv bekräfta att PC-spelet läst signalen. Inga Android-signaler är inte ensamt bevis på en frånkopplad kontroll.
5. För stödda ändringar av kontroll-API, DirectInput-mappare, SDL och Steam Input används befintlig granskning, säkerhetskopia och Ångra. Stäng spelet och öppna assistenten från biblioteket för ett nytt förslag innan **Tillämpa**. Senaste testet ligger kvar i processens minne efter spelavslut, markerat som historiskt. Ändringar får effekt nästa start. Spelarplats och detaljerade profilmappningar ändras fortfarande i GameNatives vanliga kontrollinställningar; agenten har ännu inga skrivverktyg för dem.
6. Starta igen, gör ett nytt kontrolltest och jämför med faktisk respons i samma spelmoment. Prova Ångra med stoppat spel om ändringen inte hjälper. Ingen förbättring ska påstås utifrån enbart antal registrerade signaler.

### Signalväg och avgränsning

`ControllerInputTrace` samlar bara efter användarens uttryckliga start. Android-kroken sitter före spelvyns meny-/pausspärrar, profilstadiet i `PhysicalControllerHandler` och utdata efter `WinHandler`-skrivningen till respektive spelares minnesmappade kontrollbuffert. Händelser kopplas till spelets launch-token. Sena händelser från äldre starter avvisas. Registreringen slutar senast efter 20 sekunder, vid återgång till chatten, bakgrund eller spelavslut. Nästa test/spelstart ersätter resultatet. Ingen fil skrivs och resultatet finns inte kvar efter processomstart.

Bufferten behåller högst 100 senaste händelser, 256 signalintervall och 8 anonyma kontrollnummer, med extrema värden och räknare över hela testet. Android-kroken tillåter fysiska gamepad-knappar, styrkors och tolv kända axlar; vanliga tangentbordstecken, skärmbilder, Bluetooth-adresser och enhetsdeskriptorer registreras inte. Mappade tangentbords-/musnamn avser profilens valda utdata, inte skriven text. Ingen automatisk modellfråga görs när testet startas, slutar eller granskas. Spelåtkomst och en uttrycklig analys/fråga krävs för molnanropet.

`read_controller_trace` redovisar om samma spelomgång fortfarande körs. `inspect_controllers` visar begränsade axel-/profiluppgifter, spelarplatser och aktiv `WinHandler`-status när rätt spel körs. `WINE_BUFFER` betyder att appens minnesskrivning genomförts; de rapporterade värdena är `GamepadState` före axelkodning/triggerkurva, inte avläst gästrespons. Noll äldre UDP-klienter betyder inte fel eftersom SDL/evshim kan använda delat minne. Skärmkontroller kan ge bryggskrivningar utan fysiska Android-gamepad-händelser. Räknarna kan sammanfatta flera kontroller och är inte en entydig matchning mellan varje inkommande och utgående signal.

### Verifiering

**209 tester godkända, inga fel eller överhoppade tester.** JVM-/Robolectric-tester täcker filter, avgränsning, tid/bakgrund/stopp, begränsade buffertar, knapptryck/släpp och axelvärden. Ett integrationstest kör riktig `PhysicalControllerHandler` och `WinHandler` mot en minnesmappad fil: fysisk A mappas till virtuell B, anslutningsflagga och B-byte skrivs, släpp nollställer B. Endast native-bibliotekets laddning/JNI-väckning ersätts i det testet; Wine-gästen körs inte.

Compose-tester i liggande 1280×800 dp och stående 600×960 dp verifierar start, stängd chatt under testet, kvarvarande RESUMED-aktivitet, avslutat test, analys med simulerade modellsvar, verktygsläsning, ändringsförslag och spärrad konfigurationsskrivning under spel. Layoutbilder har granskats. Befintliga assistent-, inloggnings-, fil-, mod-, uppdaterings-, FPS-, konfigurations-, stickmappnings- och inputmixningstester ingår. Bygge och tester körs med:

```sh
./gradlew :app:testModernDebugUnitTest \
  --tests 'app.gamenative.assistant.*' \
  --tests 'app.gamenative.ui.screen.xserver.PhysicalControllerHandlerTest' \
  --tests 'com.winlator.inputcontrols.PhysicalControllerStickTuningTest' \
  --tests 'com.winlator.widget.InputControlsViewStickMixingTest' \
  --tests 'app.gamenative.ui.component.FpsLimiterUtilsTest' \
  --tests 'app.gamenative.ui.component.dialog.ContainerConfigDialogContainerUpdateTest' \
  :app:assembleModernDebug -PaiDev=true
./tools/package-ai-dev-update.py
```

Använd JDK/Android-miljön som beskrivs nedan. Paket, version och APK-signatur kontrolleras även av paketeringsskriptet. Inga fysiska Android-enheter var anslutna. Bluetooth/USB på MagicPad, faktisk Wine/evshim-signalöverföring, spelrespons, ett riktigt modellsvar på kontrolltestet och installation ovanpå ai-dev.8 behöver provas på enheten. Lokala verifieringsfiler sparas i `build/ai-dev/verification/controller-input/`.

## Uppdatering 2026-10-02: assistenten under spel och livediagnostik

**1.2.1-ai-dev.8**, Android `versionCode=28`, lägger till **Quick Menu → Codex i spelet** på alla snabbmenyflikar. Samma native-agent och officiella ChatGPT-anslutning används. Ingen separat dator, ny inloggningsmetod eller API-debitering tillkommer.

### Prova på MagicPad

1. Stäng spelet. Uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.8-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-28/GameNative-AI-Dev-1.2.1-ai-dev.8.apk) ovanpå AI Dev. Paket och signeringsnyckel är oförändrade; avinstallera inte.
2. Starta ett spel normalt. Öppna snabbmenyn med Androids Tillbaka och välj **Codex i spelet** under rubriken. Spelet återupptas när menyn stängs, även vid manuell paus. På bred skärm ligger chatten till höger med spelet kvar bakom; på mindre skärm används hela bredden.
3. Vid skrivfältet väljer du **Ge spelåtkomst** om det inte redan är aktiverat för detta spel/konto. Skriv ”Undersök varför spelet hackar just nu. Läs livedata och förklara vad du faktiskt kan se.” Mätaren uppdateras lokalt; modellen anropas när du skickar en fråga.
4. Prova **Tillbaka till spelet**, fortsätt spela, öppna panelen igen och ställ en följdfråga. Konversationen följer med. Kontrollera att tangentbordet går att skriva i utan att spelet får tangenttryckningar och att fysisk handkontroll, mus och gyro åter fungerar när panelen stängts.
5. Prova att växla till en annan app och tillbaka. Pausade, saknade eller gamla mätvärden ska inte visas som aktuella FPS. I manuell paus visas **Återuppta spelet**; efter återupptagning samlas nya bildtider under två sekunder.
6. Inställningar, spelfiler och moddar får undersökas och ändringar förberedas, men **Tillämpa/Ångra** är spärrade medan spelet körs. Stäng spelet, öppna assistenten från biblioteket och be om ett nytt granskningsförslag. Historiken sparas, väntande åtgärder återskapas inte automatiskt. Modimport, modbibliotek och appuppdateringar görs från biblioteket.

### Datakälla och begränsningar

`read_live_session` är avgränsat till aktuell launch och valt app-ID. Det returnerar tidsstämplar, ålder/status, FPS, p50/p95/max-bildtid, långsamma bildrutor samt tillgängliga CPU/GPU-/temperaturvärden. Befintliga `read_performance` och `read_game_log` använder den aktuella spelomgången när den finns; äldre rapporter är bara reserv när spelet inte körs.

Mätningen återanvänder `PerformanceMetricsCollector` och renderingskrokarna: ett prov per 500 ms över överlappande tvåsekundersfönster, högst 60 prover/30 sekunder i assistentens minnesbuffert. Paus, bakgrund, uppvärmning, avsaknad av bildrutor och äldre prover markeras; de är inte aktuella gameplay-FPS. CPU-data kan avse enheten och saknade sensorer förblir okända. Frame-generation-stride och om panelen var öppen följer med varje prov. Panelen kan själv påverka mätningen. Dessa observationer är inte en jämförbar före/efter-benchmark.

Loggbufferten fångar befintlig `ProcessHelper`-stdout/stderr från just denna körning, inklusive Wine-/startverktyg, inte skärmbilden eller all Android-logcat. Högst 160 rader/24 000 tecken behålls, med begränsning av långa rader och loggstormar. Hemligheter filtreras före lagring i bufferten; privata nyckelblock filtreras även över flera rader. Varje producent har en sessionsnyckel så att sena svar från föregående körning avvisas. Bufferten töms vid avslut. Vanliga starter kan ha mycket lite loggutdata; vid behov kan ett separat debug run samla mer. Filtrering kan inte upptäcka alla privata uppgifter.

`InGameAssistantHost` ligger utanför snabbmenyns animation och öppnar en Compose-dialog i samma aktivitet. Den startar inte en separat Activity och behöver ingen Android-behörighet för att visas över andra appar. Därmed utlöses inte appens ordinarie bakgrundspaus bara av att öppna chatten. Befintlig spelåtkomst krävs för att skicka diagnostik till modellen. Ingen extra mätinsamling, automatiska modellrundor i bakgrunden, skärmbildsläsning eller godtycklig live-minnes-/filpatchning införs. XR-panelen ingår inte.

### Verifiering och bygge

Bygg med `./tools/build-ai-dev.sh` och samma JDK/Android-miljö som nedan. Tester: `:app:testModernDebugUnitTest -PaiDev=true --tests 'app.gamenative.assistant.*' --tests 'app.gamenative.ui.component.FpsLimiterUtilsTest' --tests 'app.gamenative.ui.component.dialog.ContainerConfigDialogContainerUpdateTest'`.

**174 tester godkända, inga fel eller överhoppade tester.** Lokala JVM-/Robolectric-tester täcker normal körning utan sparad debugrapport, sessions-/spelisolation, hemlighetsfiltrering och buffertgränser, paus/återupptagning/bakgrund/gamla prover, agentens verktygssvar, sidopanelens öppning/stängning, fortsatt RESUMED-aktivitet, chattens återöppning och blockerad konfigurationsskrivning under spel. Compose-layouten kontrolleras i liggande 1280×800 dp och stående 600×960 dp. Modellsvar och mätvärden simuleras i dessa tester. Befintliga assistent-, fil-, mod-, inloggnings-, uppdaterings-, FPS- och konfigurationstester körs också.

Fysisk MagicPad återstår för riktig Wine/GL/Vulkan-rendering medan dialogen är öppen, faktisk mätdata, ett live-modellsvar, Androids IME/tillbaka, fysisk handkontroll/mus/gyro, samt installation ovanpå föregående APK. Tidigare lyckad ChatGPT-verifiering på enheten är inte verifiering av dessa nya funktioner. Test- och byggresultat sparas lokalt i `build/ai-dev/verification/live-in-game/`.

## Uppdatering 2026-10-02: modhantering från chatten

**1.2.1-ai-dev.7**, Android `versionCode=27`, kopplar agenten till GameNatives befintliga modmotor. Ingen ytterligare AI-tjänst eller debitering tillkommer. Detta är fortfarande en native-agent med egna verktyg via den redan anslutna ChatGPT-planen, inte en Codex app-server eller generell datormiljö på Android.

### Prova på MagicPad

1. Stäng spelet och uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.7-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-27/GameNative-AI-Dev-1.2.1-ai-dev.7.apk) ovanpå AI Dev. Paketet `app.gamenative.aidev` och signeringsnyckeln är oförändrade. Avinstallera inte.
2. Öppna spelets assistent → **Spelåtkomst** → **Tillåt spelverktyg** och **Tillåt modhantering** → **Klart**. Modåtkomst är ny, avstängd från början och sparas per spel och ChatGPT-anslutning. Filåtkomst är ett separat val för att redigera installerade mods textkonfigurationer.
3. Tryck **+** vid meddelandefältet. Välj ett modarkiv, lösa filer eller en mapp i Androids filväljare. Paketet kopieras till modbiblioteket. Inga modfiler installeras i spelet av själva importen. Meddelandefältet fylls med en fråga om att granska paketet; tryck **Skicka**.
4. Assistenten får läsa det importerade paketets metadata, fillista och instruktioner. Kortet visar vald filplacering, vilka filer som ersätts och eventuella varningar. DLL/laddarfiler kräver kryssrutan på kortet. Tryck **Tillämpa modändring** för att faktiskt installera. Spelcontainern måste vara stoppad.
5. Starta spelet och kontrollera funktionen. Stäng spelet. **Ångra ändring** återställer försöket även efter appomstart; **Behåll** avslutar återställningspunkten inför nästa försök. Modmotorns originalbackuper behålls för senare avaktivering.
6. Skriv exempelvis ”Kontrollera mina moddar”, ”Inaktivera Test loader” eller ”Aktivera modden igen”. Avaktivering tar bort spårade modfiler och återställer original; det importerade paketet behålls. Ångra avaktivering återinstallerar samma granskade bytes.
7. **⋮ → Modbibliotek och Nexus** öppnar appens fullständiga modhantering. Här finns befintlig Nexus-inloggning/nedladdning, FOMOD-val, modprofiler och laddordning för delade filer. Dessa mer avancerade val utförs i det befintliga gränssnittet, inte automatiskt av agenten. Nexus-åtkomst förutsätter Nexus egen behörighet; ChatGPT-inloggningen ger inte den behörigheten.

### Verktyg och faktisk omfattning

| Verktyg/funktion | Vad agenten kan göra |
| --- | --- |
| `read_capabilities` | Läsa vilka anslutna verktyg och behörigheter som finns; undvika att lova verktyg som en vanlig Codex-miljö kan ha men Android-appen saknar. |
| `read_mods` | Läsa valda spelets importerade/installerade moddar, versionsnamn, status och aktiv profil. |
| `inspect_mod` | Granska paketets fillista, tillgängliga placeringsförslag, instruktioner och filspårning. Returnerar lokalt utfärdade plan-ID:n. |
| `read_mod_document` | Läsa begränsade textdokument från ett granskat paket med hemlighetsfiltrering. Pakettext behandlas som data, inte som instruktioner till verktygen. |
| `check_mod_health` | Kontrollera ägarskap, hashvärden, saknade filer och installationsjournaler. Bevisar inte funktion eller kompatibilitet inne i spelet. |
| `propose_mod_action` | Förbereda installation/återaktivering eller avaktivering för lokalt godkännande. Skriver inga spelfiler. |
| Befintliga spel- och filverktyg | Läsa diagnostik, föreslå validerade containerinställningar eller exakta ändringar i befintliga textkonfigurationer, med samma tillämpnings- och ångraflöde. |

`GameModTools` använder `NexusModManager`, `ModMaterializer`, `ModProfileManager`, `ModDeploymentCoordinator`, `ModDeploymentJournalStore` och `ModOwnershipStore`. Import återanvänder `NexusModImportService` och `LocalModImporter`. `AssistantModLibrary` återanvänder `NexusModsDialog`; befintliga Nexus-klientuppgifter och registrerad OAuth-retur ändras inte. När båda appvarianterna är installerade behöver Androids val av Nexus-retur fortfarande kontrolleras på fysisk enhet.

Chatten installerar granskade **filkopior inom spelets installationsmapp**, 1–5 000 filer åt gången. Paketinnehållet kan omfatta binära DLL/laddarfiler, men ingen Windows-installer körs och inga godtyckliga binärpatchar genereras. FOMOD/variantval, placering i andra rötter, historiska ospårade installationer och överlappande aktiva moddar hänvisas till modbibliotekets befintliga flöden. Allmän webbsökning/modsökning, generellt shell, drivrutinsinstallation, spel-UI-automation och automatiska upprepade optimeringstester är inte anslutna verktyg. En korrekt filplacering bevisar inte att modden eller dess DLL fungerar i spelets Wine-miljö.

Endast metadata, avgränsade fillistor och filtrerad dokumenttext skickas till modellen; modbinärer, käll-URI:er, Nexus-metadata med behörigheter och inloggningsuppgifter ingår inte. Filtrering kan inte upptäcka alla känsliga uppgifter. Listningar visar högst 150 moddar och 400 paketfiler; dokument är högst 64 KiB och 24 000 visade tecken. Trunkering markeras. Mod-ID måste tillhöra spelet, plan-ID måste komma från aktuell granskning, och symboliska länkar avvisas.

En återställningspunkt per spel delas mellan containerinställningar, textfiler och moddar. Privat återställningsmetadata skrivs före spelfilerna till `noBackupFilesDir/assistant/mod-undo/<appId>.json`; modmotorns originalbackuper ligger under appens befintliga `files/mods/<appId>/backups`. Metadatan innehåller före/efter-hashar, tidigare profilstatus, recept och filägarskap. Native-installationsjournalen och originalbackuper återanvänds. Spelfiler, paketfiler, profilstatus och originalbackuper kontrolleras på nytt före en relevant skrivning. Vid konflikt behålls återställningspunkten. En avbruten eller misslyckad AI-tur visar inget tillämpningsbart förslag. När en lokal skrivning väl påbörjats får den slutföra modmotorns transaktion även om chatten stängs. Ett processavbrott mitt i en modinstallation kan kräva återhämtning i modbiblioteket; det är inte testat genom att döda processen på fysisk Android.

### Verifiering för ai-dev.7

**316 tester passerar, 0 fel, 1 överhoppat** (317 totalt). Det överhoppade testet kräver ett filsystem som skiljer på stora och små bokstäver; byggdatorns macOS-volym gör inte det. Ett befintligt test fick en portabel temporär rot så att `/var` och `/private/var` inte jämförs som olika installationsmappar. Android-koden för detta ändrades inte.

Verifieringen omfattar riktig ZIP-import från en simulerad Android-dokumentprovider, granskning utan skrivning, DLL/INI-installation genom den befintliga modmotorn, avaktivering med originalåterställning och återställning från en ny instans. Ett Compose/Robolectric-test går igenom den riktiga chatten, en simulerad modellanropssekvens, README-läsning, granskningskort, DLL-godkännande, faktisk filskrivning och ångra efter att vymodellen öppnats på nytt. Separata tester täcker konto/spel-isolerad modbehörighet, avsaknad av behörighet, misslyckat slutsvar, ändrade käll-/målfiler/profiler/backuper, identiska filbytes, delade filägare, symlänkar och misslyckad backup-skrivning. Inga riktiga modell- eller Nexus-anrop görs i dessa tester.

Fysisk MagicPad återstår för systemfilväljaren/lagringsbehörigheter, stora 7z/RAR-paket via Androids native-bibliotek, Nexus-inloggning och dess retur, faktisk modellstyrd modinstallation samt spelets modkompatibilitet. Tidigare verifierad ChatGPT-anslutning på användarens enhet bevisar inte dessa nya funktioner. Inga FPS- eller kompatibilitetsförbättringar utlovas utan speltest.

Bygg med `./tools/build-ai-dev.sh` och samma Android/JDK-miljö som nedan. Relevanta JVM/Robolectric-sviter körs med `:app:testModernDebugUnitTest -PaiDev=true`, assistenttesterna samt modimport-, materialiserings-, ägarskaps-, journal-, profil-, placerings- och befintliga FPS/container-tester. Testresultat och bygglogg sparas lokalt under `build/ai-dev/verification/mod-tools/`.

## Uppdatering 2026-10-02: ändra spelets textfiler i chatten

**1.2.1-ai-dev.6**, Android `versionCode=26`, lägger till riktiga filverktyg i den befintliga native-agenten. Exempel: läsa spelets grafik-INI och föreslå en exakt inställningsändring, eller ändra konfigurationen för en redan installerad mod. Ingen debug run, API-nyckel eller separat dator behövs vid användning. Detta installerar inte en Codex app-server.

### Användning på MagicPad

1. Stäng spelet. Uppdatera via **⋮ → Appuppdateringar** eller installera [ai-dev.6-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-26/GameNative-AI-Dev-1.2.1-ai-dev.6.apk) ovanpå AI Dev. Samma paket och signerare; avinstallera inte och rensa inte appdata.
2. Öppna spelets assistent → **Spelåtkomst**. Slå på **Tillåt spelverktyg** och **Tillåt spelfiler**, läs beskrivningen och välj **Klart**. Den nya filbehörigheten är avstängd även för användare som redan beviljat spelåtkomst. Den sparas separat per spel/konto.
3. Skriv exempelvis: ”Läs spelets grafikinställningar och föreslå en ändring till 30 FPS om spelet stöder det.” För en installerad mod kan du ange dess konfigurationsfil och vad du vill ändra. Assistenten hittar filen själv i tillgängliga mappar.
4. Kortet **Föreslagen filändring** visar filens relativa sökväg, skäl och exakta borttagna/tillagda textrader. Tryck **Tillämpa filändring** eller **Avstå**. Appen kräver stoppad spelcontainer, kontrollerar originalet igen och säkerhetskopierar det innan skrivning.
5. Starta spelet och kontrollera att inställningen används. Stäng spelet och välj **Ångra ändring** för att återställa originalfilen, även efter appomstart. **Behåll** tar bort återställningspunkten efter bekräftelse och tillåter nästa försök.
6. Prova omstart av appen, avstängd filåtkomst och kontobyte. Ett väntande förslag ska inte återkomma från historiken eller flyttas till ett annat konto. Ångra en redan tillämpad ändring går även när filåtkomst har stängts av.

Om spelet skriver om samma fil efter testkörningen avvisas återställningen och backup behålls. Ingen automatisk överskrivning eller sammanfogning görs. Den senaste filbackupen ligger privat i `noBackupFilesDir/assistant/file-undo/<appId>.json`; konfigurationsbackupen är fortsatt kompatibel med tidigare versioner. En backup totalt per spel gäller för både containerinställningar och textfiler. Hemlighetsfiltrering kan inte identifiera all känslig information.

### Filverktyg och avgränsning

| Verktyg | Verklig funktion |
| --- | --- |
| `list_game_files` | Söker i GameNatives registrerade spelmapp och befintliga privata Wine-mappar för det valda spelet. Returnerar lokalt utfärdade fil-ID:n, relativa namn och storlekar. Modellen får inga valfria sökvägar. |
| `read_game_file` | Läser en listad fil, filtrerar hemligheter och binder originalets bytes till aktuell tur. Råfiler och verktygsresultat sparas inte i chatthistoriken. |
| `propose_file_edit` | Förbereder 1–4 exakta, unika, icke överlappande textbyten i en läst fil. Appen genererar diffen. Resultatet är ett förslag; endast användarens knapp kan skriva. |

`GameFileRoots` återanvänder butikernas installationsmetadata och `ModContainerResolver` för spelets Wine-prefix. Den anropar inte containerfunktioner som skapar eller migrerar data. `GameTextFiles` återanvänder modhanteringens `ModTargetResolver` för Windows-sökvägar och tvetydiga filnamn. Befintlig modhantering för arkiv/DLL:er är fortfarande separat från AI-verktygen.

Stödda befintliga filer: **INI, CFG, CONF, JSON, XML, TOML och PROPERTIES**, högst **128 KiB och 48 000 tecken**. UTF-8 med/utan BOM samt BOM-märkt UTF-16 LE/BE stöds. Oförändrade bytes, BOM, Windows-radbrytningar och blandade oförändrade radslut bevaras. JSON och XML kontrolleras för syntax; andra format kräver att modellen väljer rätt inställningar. Inget generellt syntaxtest bevisar att ett spel stöder eller följer inställningen.

Sökningen har gränser: 5 000 poster, 8 undermappsnivåer och 60 träffar. Ett avkortat resultat markeras för modellen. Symboliska länkar (även Wine Documents-länkar till delad Android-lagring), dolda mappar, kända spar-/cachemappar och sökvägar som ser ut att innehålla inloggningsdata utesluts. Det kan innebära att en viss konfiguration inte kan nås i denna version. Binärer, DLL/EXE, arkivinstallation, skript, nya/raderade filer, generella textkodningar och automatiska upprepade optimeringstester ingår inte.

Hash/byte-kontroll krävs efter läsning och före skrivning. Backup skrivs atomiskt i privat lagring före atomiskt filbyte. Återställning kräver att filen fortfarande motsvarar den tillämpade versionen eller originalet; vid konflikt behålls backup. Kodningen och originalfilen återställs byte för byte. Textbyten får inte beröra filtrerade rader eller privata nyckelblock, även om modellen gissar deras innehåll. Inga filer ändras av ett misslyckat/avbrutet modellflöde. Väntande filförslag sparas inte över appomstart.

### Verifiering för ai-dev.6

**150 tester passerar, 0 fel, 0 överhoppade.** Filtesterna omfattar förhandsvisning utan skrivning, normal tillämpning, exakt återställning från ny instans, UTF-8/UTF-16/BOM/radslut, hemlighetsfiltrering, gissade privata textdelar, konflikter, utebliven backup-skrivning, dubbletter/överlapp, ogiltiga fil-ID:n, sökvägsbyte, symlinkbyte, storleksgränser, JSON/XML och XML-entiteter. Android-tester verifierar begränsade Wine-mappar, ingen skapad mapp vid läsning, stoppad container, gemensam backup, kontoisolering och separat filåtkomst. Ett Compose/Robolectric-test klickar genom chatt → diff → tillämpning → ångra mot en verklig temporär INI-fil. De tidigare auth-, ström-, uppdaterings-, inställnings- och FPS-testerna ingår också.

Modellanrop simuleras i dessa tester; de bevisar inte modellens verkliga filval eller en speloptimering. Ingen fysisk MagicPad har använts för denna version. OEM-lagring/behörigheter, verkliga spelvägar och spelets beteende måste testas på enheten. Krypterad historik använder samma Android Keystore-lagring som ai-dev.5; historiktester injicerar en minneslagring. Ingen FPS-vinst har mätts eller utlovats.

Byggning: samma instruktioner och `tools/build-ai-dev.sh` längre ned. APK: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.6.apk`. Paket `app.gamenative.aidev`, kod 26 och tidigare signerare är verifierade. SHA-256: `c5aed9ca45af92fff1b3ecf71fc283a17683f402910b4c24d541b04d6b94c25c`. Bygglogg: `build/ai-dev/verification/file-tools-final-build-tests.log`. Test-XML: `build/ai-dev/verification/file-tools/`. [Release ai-dev-26](https://github.com/stenerstrom/GameNative/releases/tag/ai-dev-26).

## Uppdatering 2026-10-02: en spelagent och en enklare chatt

**1.2.1-ai-dev.5**, Android `versionCode=25`, ersätter formuläret med en chatt som har fast skrivfält längst ned, läsbara svar med fetstil/kod, spelets namn i toppen och konto/modell/uppdateringar under menyn **⋮**. Den gamla diagnostikpanelen, den obligatoriska manuella läsningen och den framträdande verifieringsknappen är borttagna från huvudflödet. Den senaste konversationen återöppnas för samma spel och anslutning.

### Kort arbetsplan och genomförd arkitektur

1. **Ge agenten verktyg, inte bara mer prompttext.** Ett avbrytbart agentflöde växlar mellan Responses-anrop och lokala verktyg, högst åtta anrop per användarmeddelande. Varje verktygsresultat skickas tillbaka med rätt `function_call_output.call_id`. Modellen får sedan analysera resultatet, undersöka vidare och föreslå en åtgärd. Ingen separat dator behövs.
2. **Återanvänd faktisk spelkonfiguration.** En typad katalog beskriver 20 verifierbara inställningar, deras nuvarande värden, tillåtna val och begränsningar. Modellen kan inte välja godtyckliga nycklar, filer eller kommandon. En saknad förmåga blir ett konkret besked, inte ett påstående om att något redan är åtgärdat.
3. **Låt granskning och återställning vara en del av chatten.** Ett kort visar gamla och nya värden samt **Tillämpa** och **Avstå**. Efter tillämpning finns **Ångra ändring** och **Behåll**. Behåll har en bekräftelse som förklarar att den gamla återställningspunkten tas bort, så nästa försök kan få en ny backup.
4. **Spara sammanhang utan att blanda konton.** De senaste åtta utbytena och spelåtkomstvalet lagras med AES-GCM och Android Keystore, separat per spel och utfärdat anslutnings-ID, i `noBackupFilesDir`. Råa loggar, verktygsanrop och väntande godkännanden sparas inte. AI-svar kan innehålla filtrerade utdrag från diagnostik. **Ny chatt** rensar konversationen; backup påverkas inte.

### Verktyg och rättigheter

Spelåtkomst är avstängd tills användaren väljer **Ge spelåtkomst → Tillåt spelverktyg**. Appen förklarar att konfiguration, tillgänglig spellogg, historiska prestandamätningar och upptäckta handkontroller kan skickas till OpenAI. Valet sparas för detta spel/konto. Därefter väljer agenten själv vad som behövs; användaren behöver inte bifoga eller kopiera JSON varje gång. Utan spelåtkomst går vanlig chatt fortfarande att använda.

| Verktyg | Beteende |
| --- | --- |
| `read_configuration` | Läser vald container, enhetsmodell, redigerbar katalog och om backup finns. Binder ett konfigurationshash till denna tur. |
| `read_game_log` | Läser en begränsad och hemlighetsfiltrerad logg för just spelet. Saknad logg är ett resultat, inte ett stopp för chatten. |
| `read_performance` | Läser matchande sparade mätningar och senaste sessionsmetadata, uttryckligen historiska och inte ett jämförbart före/efter-test. |
| `inspect_controllers` | Läser Android-upptäckta gamepads/joysticks och GameNatives aktuella spelarplatsstatus. Inga serienummer, Bluetooth-adresser eller enhetsdeskriptorer skickas. Det är ingen knapptryckningstest inne i spelet. |
| `propose_settings` | Validerar 1–8 relaterade ändringar efter att konfiguration lästs. Förbereder förhandsvisningen med gamla/nya värden. Returnerar `awaiting_user_approval`, aldrig tillämpad. |
| `request_restore` | Visar återställningsåtgärden om backup finns. Skriver inte själv någon konfiguration. |

De 20 inställningarna är upplösning, FPS-begränsning av/på och mål, XInput/DirectInput/Auto, DirectInput-mappning, SDL-kontroll-API, Steam Input, inaktivering av mus, touchskärmsläge, shooter-läge, vibration, ALSA/PulseAudio, låg ljudlatens, renderer-presentation (fifo/mailbox), Box64-profil, DRI3, porträttläge, extern skärms inmatningsläge, skärmbyte och pauspolicy.

`AUTO` för kontroll-API är automatiskt val, inte avstängda kontroller. Mappern är separat från att aktivera XInput. Att en kontroll är upptäckt av Android eller en API-inställning är aktiv bevisar inte att den fungerar i Dark Souls. Box64-profilen påverkar inte ett spel som använder FEX. Inställningskatalogens hjälptexter förklarar sådana begränsningar.

Skrivning sker bara från appens tillämpningsknapp, kräver stoppad spelcontainer och oförändrat hash och skapar backup först. Återställningen bevarar andra ändringar och avvisar konflikter. Befintliga backup-filer fungerar. En förberedd åtgärd accepteras inte om nästa modellsteg misslyckas eller avbryts, och återställs aldrig automatiskt från chatthistoriken.

### Varför ingen inbäddad Codex-process ännu?

Den fungerande officiella OAuth-anslutningen behålls. [OpenAI:s app-server-dokumentation](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server) beskriver en separat `codex app-server`-process med stdio och anslutningens OAuth-token; den etablerar inte ett färdigt Android-paket. Att lägga till den processen skulle inte automatiskt ge GameNative-verktyg eller en bra Android-UI. Implementationen är därför en **native spelagent med egna verktyg över Responses**, och marknadsförs inte som en installerad Codex-runtime.

[Preview-kraven](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) följs: `store:false`, `stream:true`, hela nödvändiga sammanhanget i `input`, namespacade function-verktyg och inget `previous_response_id` via HTTP. Modellsvar och resonemangsobjekt återges till nästa steg inom samma tur; krypterat resonemang begärs via `include: ["reasoning.encrypted_content"]`. [Function calling](https://developers.openai.com/api/docs/guides/function-calling) beskriver kedjan anrop → lokalt verktyg → verktygsresultat → fortsatt modellsteg. Ingen API-nyckel, separat API-debitering eller extern server införs.

### Testa uppdateringen på MagicPad

1. Stäng spelet. Välj **App updates** i ai-dev.4 (i nya vyn **⋮ → Appuppdateringar**) och installera uppdateringen, eller öppna [ai-dev.5-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-25/GameNative-AI-Dev-1.2.1-ai-dev.5.apk) över AI Dev. Avinstallera inte.
2. Öppna ett spel → AI-assistenten. Välj **Ge spelåtkomst**, läs beskrivningen och aktivera **Tillåt spelverktyg**. Tryck **Klart**. Det valet behöver inte upprepas för samma spel/konto.
3. Skriv exempelvis ”Min handkontroll fungerar inte. Kontrollera inställningarna och föreslå en ändring om det behövs.” Agentens pågående undersökning syns i chatten. Ingen debug run behövs.
4. Granska ändringskortet och tryck **Tillämpa**. Kontrollera inställningen i spelets Edit container. Starta en ny spelsession och prova. Om inställningen redan är rätt ska agenten förklara det och undersöka nästa möjliga orsak.
5. Stäng spelet och öppna chatten igen. Kontrollera att historiken finns kvar och välj **Ångra ändring**, eller **Behåll** om försöket ska bli det nya utgångsläget.
6. Prova ”Spelet startar inte”, ”Jag får inget ljud” och en FPS-fråga. Saknade loggar/mätningar ska beskrivas ärligt. Prova även ett annat konto/spel, avbruten nätförbindelse och **Stoppa**; ingen ändring ska tillämpas utan knapptryck.

Fysisk spelkörning, Android Keystore-lagring av den nya chatthistoriken och modellens verkliga flerstegsval kräver MagicPad-verifiering. Tidigare fotografier verifierar inloggning, AI-anrop och vanlig chatt, inte denna nya agentversion. Ingen förbättring av kontrollkompatibilitet eller FPS påstås.

### Verifierat för ai-dev.5

**128 utvalda tester passerar, 0 misslyckade, 0 överhoppade.** Det omfattar agentens verktygsloop och matchande anrops-ID:n, fel och avbrott, förslag före godkännande, samtliga 20 inställningars läsning/sparning/återställning genom GameNatives containerläsare, chattens kontoisolering och återöppning, samt befintliga auth-/ström-/uppdaterings-/FPS-tester. Ett Compose/Robolectric-test i surfplatteformat verifierar skrivfält, sändning utan logg, formaterat svar och separat åtkomstpanel; åtkomstreglagets semantiska klick testas utan fysisk touch.

Appens nya samtalslagring använder Android Keystore, men test av historikflödet injicerar en minneslagring. Den riktiga krypterade persistensen och OEM-tangentbord/layout måste kontrolleras på MagicPad. Ingen fysisk enhet har använts för test av denna version och inga verkliga fleranropssvar har hämtats från användarens konto i byggmiljön.

APK:n bygger och paketverktyget verifierar paket `app.gamenative.aidev`, versionskod 25 och samma fastlagda signerare som tidigare APK:er. [Release ai-dev-25](https://github.com/stenerstrom/GameNative/releases/tag/ai-dev-25). Fil: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.5.apk`. SHA-256: `135814d4d29ade98a26f083fa61397b4a3fccb647fada90f72d08479eb4477bd`. Bygglogg: `build/ai-dev/verification/agent-final-build-tests.log`. Test-XML: `build/ai-dev/verification/agent/`.

### Fortsatt utveckling

Att kunna ”fixa allt möjligt” kräver fler granskade förmågor, inte obegränsad åtkomst till appens privata katalog. Nästa steg är val av faktiskt installerade drivrutiner/Wine-versioner med hantering av deras sidofiler, valda spels INI-format med förhandsvisad diff och filbackup, samt jämförbara prestandatester. Bluetooth-parning, skrivning till globala spelarplatser och automatiska ingrepp i PC-spelets UI finns inte i denna version. Varje ny förmåga ska ha verklig läsning, validering, tillämpning, återställning och testfall innan den erbjuds till modellen.

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

Denna tidigare uppdatering: [GitHub-release ai-dev-24](https://github.com/stenerstrom/GameNative/releases/tag/ai-dev-24). APK: `build/ai-dev/GameNative-AI-Dev-1.2.1-ai-dev.4.apk`. SHA-256: `f9fb595a9cfc387575e3e1c6490b67a5723b82a835fd9a2aa77baf2390c198f3`.

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
| `inputType`/`dinputMapperType` | Samma sparade kontroll-API och mappning som `ControllerTabContent`, `ContainerUtils.toContainerData` och nästa spelstarts WinHandler/SDL-konfiguration använder. |
| `ModDiagnosticSanitizer` | Grundfilter för sökvägar, URL-frågor och hemligheter, kompletterat med OAuth-token, lösenord, cookies, JWT, e-post och privata nycklar. |
| Befintlig AI debug run | Samlar loggar + PerfSampler-data och erbjuder rapport till GameNatives Discord-relay. Den är inte en lokal ChatGPT-provider. Rapporten kan behållas lokalt och läsas av assistenten. |

Konfigurationen som skickas är en allowlist: upplösning, grafik-/översättningskomponenter, FPS- och kontrollinställningar samt den redigerbara katalogen ovan. Hela `envVars`, startargument, enhetsserienummer, kontodatabaser och logcat skickas inte. Storleksgränser gäller för rålogg, komprimerad logg, rapport och modellström. Komplett begränsad logg filtreras före avkortning. Användaren ser och kan redigera diagnostiken före sändning. Filtrering kan inte identifiera varje tänkbar hemlighet.

`GameAssistantTools` binder alla läsningar och skrivningar till app-ID:t från Android-skärmen. Ingen modell får välja filvägar. Förslag valideras oberoende av modellen. Tillämpning kräver användarens knapptryck, stoppad spel/container-session och oförändrad konfigurationshash. Backup skrivs atomiskt före konfigurationen; ett misslyckat backup-skrivförsök stoppar ändringen. Bara ett utestående experiment per spel tillåts.

Backup i `noBackupFilesDir/assistant/undo/<appId>.json` innehåller original och förväntat efterläge. Återställning skriver bara berörda fält och bevarar andra ändringar. Om ett berört fält ändrats manuellt till ett tredje värde blockeras återställningen med backup kvar. Den överlever processomstart. Den raderas vid avinstallation/rensning av appdata. Detta är inte en backup av sparfiler, Wine-registret eller spelinstallationen.

## Separat app och paketgranskning

Flaggan `-PaiDev=true` aktiverar funktionen endast i debug-bygget:

- Appnamn: **GameNative AI Dev**.
- Paket-ID: **`app.gamenative.aidev`**.
- Version: upstream-version med suffix och egen stigande versionskod från `ai-dev.properties` (nu `-ai-dev.5`, kod 25).
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

Följ de aktuella teststegen för ai-dev.5 överst i dokumentet. Uppdatera från ai-dev.4 med **App updates** eller installera `build/ai-dev/GameNative-AI-Dev.apk` över samma app. Med USB-felsökning kan `adb install -r build/ai-dev/GameNative-AI-Dev.apk` användas. Avinstallera inte för att uppdatera.

Vid helt ny installation: starta AI Dev och slutför GameNatives hämtning av körmiljön. Logga in i spelbutiken eller lägg till ett eget spel. Starta spelet en gång eller skapa dess container med **Edit container** innan agenten undersöker inställningarna. **AI debug run** eller **Play with diagnostics** är valfria sätt att samla en spellogg; behövs inte för vanlig chatt eller konfigurationsanalys. Skicka inte rapporten till Discord för att använda assistenten.

Om ingen anslutning finns, välj **Continue with ChatGPT**. Samtycke sker på OpenAI:s sida. Återvänd med Androids Tillbaka om fliken ligger kvar och kontrollera appens status. Kontot eller modellistan ensamma bevisar inte AI-behörighet: ett faktiskt avslutat textsvar gör det. Spara bara felkod/request-ID vid problem, aldrig tokens eller hela OAuth-returadressen.

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

Verifiera ai-dev.5:s faktiska flerstegssvar, uppdateringsinstallation, krypterade historik efter appomstart och kontroll-/ljud-/prestandaändringar på MagicPad. Därefter utökas katalogen med installerade drivrutiner/Wine-versioner och deras verkliga sidofiler, spelbundna INI-adaptrar och jämförbara före/efter-mätningar. Agentens läs- och förslagsloop finns nu; godtycklig shell-/filåtkomst och automatiska upprepade speltester gör det inte. Inloggningsflikens automatiska återgång är fortfarande inte verifierad.
