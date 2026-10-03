# GameNative AI Dev – MagicPad-prototyp

Utgångspunkt: upstream `utkarshdalal/GameNative`, commit `375785a7f416ff5bcf2da90ca8cc3cf8b29e21f9`.
Fork: https://github.com/stenerstrom/GameNative. Gren: `magicpad-ai-prototype`.
Kontrollerad dokumentation: 2026-10-03. Ingen `AGENTS.md` fanns i denna upstream-version.

## Uppdatering 2026-10-03: webbsökning i chatten

**1.2.1-ai-dev.17**, Android `versionCode=37`, kopplar in OpenAI:s officiella webbverktyg. Tidigare hade agenten endast lokala spelverktyg: internetbehörighet och ett fungerande modellanrop gav inte automatiskt modellen webbsökning.

Uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.17-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-37/GameNative-AI-Dev-1.2.1-ai-dev.17.apk) över AI Dev. Paket och signeringsnyckel är samma som tidigare; **avinstallera inte och rensa inte data**.

### Användning

- **Webb på** vid skrivfältet gör webbsökning tillgänglig i vanlig chatt, i spelet och vid analys av en vald felsökningsrapport. Ingen debug run, spelåtkomst eller filbehörighet behövs för att bara söka på nätet.
- Skriv exempelvis “Sök efter aktuella lösningar för Dark Souls Prepare to Die Editions kontrollproblem och länka originalkällorna”, eller klistra in en offentlig dokumentationsadress och be Codex läsa den. Agenten kan kombinera sökningen med aktuell speldata när du har aktiverat spelåtkomst.
- **Söker på webben…** visas när OpenAI skickar en sökhändelse. Källorna är understrukna, klickbara länkar intill svaret och följer den sparade chatten. En aktiverad Webb-knapp betyder att verktyget erbjuds, inte att varje meddelande har utlöst en sökning.
- Tryck **Webb på / Webb av** för att ändra läget. Valet sparas separat per spel och ChatGPT-anslutning och ändrar inga spelbehörigheter. **⋮ → Webbsökning** beskriver funktionen. Nya och äldre konversationer som saknar ett sparat val har Webb på.

### Integration och gränser

Samma OAuth-token och `POST https://api.openai.com/v1/responses` används med `store:false`, `stream:true` och `tools:[{"type":"web_search","external_web_access":true}]`, tillsammans med befintliga spelverktyg där de är tillåtna. Ingen ny betaltjänst, API-nyckel, Codex app-server eller separat dator tillkommer. Modellen väljs fortsatt från kontots katalog.

[OpenAI:s token-sharing-begränsningar](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) anger att webbsökning styrs av modell och konto-/arbetsplatspolicy. [Webbverktygets dokumentation](https://developers.openai.com/api/docs/guides/tools-web-search) beskriver sökning, sidläsning för stödda modeller och `url_citation`. Appen hanterar källor både i avslutade svar och separata strömningshändelser. Den skickar inte sökfrågor till en egen söktjänst och kopierar inte OAuth-token till webbplatser.

Ett avslag från OpenAI som pekar på webbverktyget visar en förklaring och behåller felkod, parameter och tillgängligt request-ID. Välj en annan modell i kontots lista eller slå av Webb och skicka igen. Appen byter inte konto eller debiteringssätt och återförsöker inte automatiskt. Kvot- och inloggningsfel ska inte beskrivas som ett bevis på saknad webbbehörighet. Avbrutna eller misslyckade svar godkänner inga förslag.

Webbverktyget läser offentliga källor. Inloggade webbläsarsessioner, automatisk filnedladdning och installation från en länk ingår inte. Befintlig Nexus-/modhantering används fortsatt för sådana granskade paket. Webbsidor är opålitligt underlag; de får inte ge agenten instruktioner eller godkänna ändringar. Aktuell konfiguration måste fortfarande läsas före ett ändringsförslag, och Tillämpa/Ångra används som tidigare.

Agenten instrueras att söka med minimala offentliga uppgifter, exempelvis spel, version och generell feltext, aldrig med råloggar, kontouppgifter eller privata sökvägar. Detta är en modellinstruktion, inte en fullständig garanti för sökfrågornas innehåll. Frågor och svar bevarar normala offentliga webbadresser; URL:er med inloggningsuppgifter och identifierade hemliga parametrar filtreras. Den tidigare filtreringen av råa spelbilagor behålls. Bilder granskas fortfarande separat av användaren.

### Verifiering och test på MagicPad

Automatiska tester täcker webb utan spelåtkomst, webb tillsammans med agentens spelverktyg, avstängt läge, konto-/spelisolerat val, källor i slutligt och strömmat svar, fel/avbrott, hemlighetsfiltrering och bevarade offentliga URL:er. UI-flödet körs i 360 × 800 och 1280 × 800: skicka, visa sökstatus, tryck på en riktig källänk via renderad text, stäng av Webb och skicka igen. Bilderna granskas visuellt. Molnsvaret är simulerat i dessa tester; verklig behörighet för användarens konto är inte verifierad här.

Slutresultat och APK-verifiering sparas lokalt under `build/ai-dev/verification/web-access/`. Ingen kontroll-, JNI-, SDL- eller native-bryggkod ändras. Bygg- och installationskommandon längre ned gäller även denna version.

**354 tester godkända, inga fel eller överhoppade tester.** `assembleModernDebug -PaiDev=true` lyckades. Sviten omfattar assistenten, `com.winlator.inputcontrols.*`, `PhysicalControllerHandlerTest`, `FpsLimiterUtilsTest` och `ContainerConfigDialogContainerUpdateTest`; övriga upstream-tester ingår inte. Ingen fysisk Android-enhet var ansluten.

På MagicPad: uppdatera över befintlig app, öppna samma chatt och kontrollera **Webb på**. Ställ exempelfrågan ovan, kontrollera sökstatus och öppna en källänk. Prova sedan Webb av och en vanlig fråga, samt en sökning med spelet igång. Om kontot/modellen nekar webbsökning, skicka feltexten utan inloggningsuppgifter. Ingen verklig webbsökning genom MagicPads konto har körts i utvecklingsmiljön.

## Uppdatering 2026-10-03: felsökningsrapporter till Codex

**1.2.1-ai-dev.16**, Android `versionCode=36`, ersätter Discord-flödet för debugrapporter i AI Dev. Uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.16-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-36/GameNative-AI-Dev-1.2.1-ai-dev.16.apk) ovanpå befintlig AI Dev. Paketet `app.gamenative.aidev` och signeringscertifikatet är oförändrade. Avinstallera inte och rensa inte data.

### Användning på MagicPad

1. Välj **Felsök med Codex** i spelets meny, eller **Quick Menu → Codex i spelet → Felsök**. Om en tidigare rapport visas väljer du **Ny insamling**.
2. Välj **Krasch / startfel**, **Hack / FPS**, **Kontroll** eller **Annat**. Från biblioteket startar **Starta spel och samla** spelet. Under spel använder **Samla från pågående spel** befintlig utdata och mätningar utan omstart. Bara Krasch/startfel vid en ny start lägger till extra Wine-loggning; redan körande processer får inte nya startflaggor i efterhand.
3. **Markera fel** i den lilla spelpanelen sparar tidpunkten. **Codex** öppnar chatten. Kontrollvalet begär det befintliga 20-sekunderstestet en gång för rapporten. Tryck knappar/spakar och kontrollera om spelet reagerar. Om spelet inte är redo visas hur du startar testet manuellt under **Kontroll och input**. Insamlingen ändrar inga sparade inställningar eller inputdestinationer.
4. Avsluta spelet eller välj **Avsluta insamling**. Rapporten kan också analyseras medan spelet körs. **Analysera med Codex** skickar frågan och ger agenten läsåtkomst till den valda, filtrerade rapporten via befintlig ChatGPT-anslutning. **Visa underlag före analys** visar översikt, logg, mätningar och input med sidvisning. Bilder bifogas separat med befintlig bildknapp.
5. Rapporten visas i samma chatt. Codex kan söka efter fel och läsa tidsintervall kring markeringar. Ändringar använder befintliga granskningskort och Ångra. Historiska inställningar ger inte skrivbehörighet: aktuell konfiguration måste läsas och valideras före ett förslag. Permanenta fil-/mod-/inställningsändringar kräver avslutat spel; befintliga stödda liveförsök i kontrollbryggan är separata.
6. Efter appavbrott eller nätverksfel: öppna spelets chatt och **Felsök**, välj rapport och begär analys igen. Inga AI-anrop återförsöks automatiskt. Ett rapportutkast sparas före startförsöket; kända startfel kan sparas innan någon spelprocess observerats. Efter processdöd återfinns senaste sparningen som ofullständig.
7. **Byt rapport** väljer en tidigare körning. En analyserad rapports koppling följer chatten för just detta spel och ChatGPT-konto, även efter omstart. **Koppla loss**, Ny chatt eller avstängd spelåtkomst tar bort kopplingen. Ett annat konto ärver inte den. **Importera äldre lokal debugrapport** hämtar en tidigare sparad Discord-formatrapport lokalt; originalet behålls och ingen överföring sker före begärd analys.

### Underlag, avbrott och gränser

Varje rapport har eget ID och spelomgångens sessions-ID, en filtrerad inställningsbild från starten, app-/enhetsversion, tillgängliga modnamn/versioner/status med insamlingstid, processutdata, bildtider/sensorer och eventuell kontrollobservation. Modmetadata visar appens deklarerade tillstånd, inte att spelet laddat modden. Saknade uppgifter förblir okända. Legacy-importer märks med osäker koppling mellan startinställningar och logg; deras gamla prestandarapport blandas inte in som en verifierad matchning.

Insamlingen återanvänder befintliga avläsningsbuffertar och sparar atomiskt ungefär var tredje sekund i privat `noBackupFilesDir`. Ingen extra sensormätare eller generell logcat-inspelning startas. Vid normal avslutning tas en sista kopia innan livebufferten töms. Vid processdöd kan sista osparade delen saknas. Ett lagringsfel ska inte stoppa spelets avslutning; föregående atomiska kopia behålls.

Gränser: 1 000 loggrader/160 000 loggtecken, 600 mätprover (ungefär fem minuter vid 500 ms intervall), 30 markeringar och 1,5 MB per rapport. Livebufferten behåller högst 160 rader/24 000 tecken; loggstormar kan förlora rader mellan sparningarna. Identiska rader mottagna i samma millisekund slås ihop. Bortfall och tidsstämplar redovisas. Äldre markeringar kan ligga utanför kvarvarande mät-/loggfönster. Högst tio rapporter per spel och fyrtio totalt behålls vid nästa insamlingsstart; en pågående rapport undantas. Chattsvar och återställningskopior gallras inte med rapporterna.

Hemligheter filtreras före livebufferten, inklusive privata nyckelblock över flera rader. Inställningar använder en tillåten fältlista; miljövariabler, OAuth-uppgifter, installationsarkiv, spelbinärer och sparfiler ingår inte. Filtrering kan inte identifiera alla privata fritextuppgifter, så underlaget går att granska. Bilder skickas aldrig automatiskt. Loggar och metadata behandlas som opålitligt underlag, inte instruktioner till agenten.

Analysen använder en fryst kopia av vald rapport under hela verktygsomgången. Verktygen tar inte godtyckliga filvägar eller andra spel-ID:n. Logg-/mätfrågor använder bokstavlig textsökning, tidsintervall och paginering: högst 60 rader/18 000 tecken per svar. Kontrollavsnittet använder den befintliga begränsade observationen. Rapporter raderas inte efter ett AI-svar. Rapport-ID och chattsvar följer den krypterade konversationen; råa verktygsresultat kopieras inte dit.

Extra loggning, sparning och öppen chatt kan påverka prestandan. Jämförande FPS-mätning spärras under rapportinsamling, och ett pågående jämförelsetest avslutas som otillförlitligt om felsökning startas. Efter stoppad normal insamling kan ett separat jämförelsetest göras. En körning med extra startloggning kräver vanlig ny spelstart för jämförbara mätningar. Ingen förbättring eller 120 FPS utlovas.

### Implementation och verifiering

`CodexDebugReportStore` sköter lagring, storleksgränser, återhämtning och begränsade läsningar. `CodexDebugSession` binder insamlingen till spelomgången. `CodexDebugUi` ger rapportkort, val och markering. `GameAssistantAgent` återanvänder befintlig native-agent och ChatGPT-provider med två nya läsverktyg. Ingen Codex app-server, extern dator eller separat API-debitering tillkommer.

I AI Dev går start-/slutflödet till assistenten. Discord-dialog, Discord-inloggning och betalvägg ingår inte i debugflödet. `DebugReportApi.submit` avslår anrop före autentisering/nätverksåtkomst. Upstream-flödet behålls bakom byggflaggan. Detta gäller debugrapporter; övriga butikstjänster, supportlänkar och appens analysinställningar är separata.

Slutresultat och underlag sparas i `build/ai-dev/verification/codex-debug/`. Tester täcker tom Wine-logg, spel-/rapportisolering, sökning/paginering, hemligheter, symlänkar, avbrott/återhämtning, tidiga startfel, sista loggkopian, kontoavgränsad rapportkoppling, explicita AI-anrop/manuell återförsökning, validering av inställningar, kontrollvägar och spärrade FPS-jämförelser. Chatten/rapportgranskningen provas och renderas i 360 × 800 och 1280 × 800. AI-svar, sensorer och processlivscykel är simulerade.

**341 tester godkända, inga fel eller överhoppade tester.** `assembleModernDebug -PaiDev=true` lyckades. Sviten omfattar `app.gamenative.assistant.*`, `com.winlator.inputcontrols.*`, `PhysicalControllerHandlerTest`, `FpsLimiterUtilsTest` och `ContainerConfigDialogContainerUpdateTest`. Den omfattar inte alla övriga upstream-tester. Vid paketering verifieras paket/version/signering och samtliga native-bibliotek jämförs med ai-dev.15.

Ingen fysisk Android-enhet är ansluten här. Verkliga Wine-krascher, processdöd under spel, molnsvar via kontot, lagringsbelastning och input med den nya panelen behöver provas på MagicPad. Börja med Bloodstained: verifiera kontrollen före/under/efter insamling, markera ett problem, avsluta, analysera och kontrollera att samma körning visas. Ingen JNI-, SDL- eller native-bryggkod har ändrats.

## Uppdatering 2026-10-02: spelverktyg, profiler och bilder

**1.2.1-ai-dev.15**, Android `versionCode=35`, samlar funktionerna under **Spelverktyg** i chatten. Installera över befintlig AI Dev via **⋮ → Appuppdateringar** eller [uppdaterings-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-35/GameNative-AI-Dev-1.2.1-ai-dev.15.apk). Paketet `app.gamenative.aidev` och signeringsnyckeln är desamma. Avinstallera inte och rensa inte appdata.

| Funktion | Så använder du den | Omfattning |
| --- | --- | --- |
| Namngivna återställningsprofiler | Spelverktyg → Återställningsprofiler → namnge → Granska och spara | Sparade grafik-/runtimeval, kontrollmappning och spårade modval. Återställning visar ändringar och skapar en separat, beständig **Ångra profil**. |
| Kontroll före start | Spelverktyg → Kontroll före start; körs även från bibliotekets vanliga Spela-knapp | Start-EXE, felaktig FPS-begränsare, loggens saknade DLL:er, luckor i numrerade BIN-filer, ledigt utrymme och spårade modfiler. Varningar kan granskas eller passeras med Starta ändå. |
| Spelbilder till Codex | Quick Menu → Codex i spelet → **Bifoga spelbild** | En bild av spelets renderingsyta. Tryck på miniatyren för större förhandsvisning. Skickas först tillsammans med nästa meddelande; kan tas bort före sändning. |
| ZIP/7z och komponenthjälp | Bibliotek → + → Installera offlinespel med Codex → **Välj ZIP eller 7z** | Separat kopia, säker uppackning, EXE/BIN-struktur bevarad. Fortsätt förbereder spelets Wine-miljö före chatten. Codex kan föreslå medföljande VC++/DirectX-EXE via befintlig installerarfunktion. |
| Analys av hackande | Spelverktyg → **Be Codex undersöka hackandet** | Samlar aktuella inställningar, sparat FPS-mål, jämförbara mätningar, senaste bildtider och tillgängliga sensorer. Skiljer observationer från hypoteser. |
| Kontrollprofiler och knappbyte | Spelverktyg → Kontroller, eller skriv exempelvis “Byt A till B för detta spel” | Kopierar en biblioteksprofil eller ändrar en knapp i en egen profil för valt spel. Sticks och Home/MODE bevaras vid enstaka knappbyte. Signalväg och spelarplats kan undersökas med befintliga liveverktyg. |
| Modprofiler och filkonflikter | Spelverktyg → Modprofiler → välj paket/ordning och spara | Exempelvis Original, Grafik och Gameplay. Senast valda mod får högst filprioritet. Visar överlappande filer och aktiv vinnare. Byt profil med förhandsgranskning, uttryckligt modgodkännande och ångra. |

### Återställning och avgränsning

Profiler är användarsparade lägen, inte automatiskt verifierade kompatibilitetsrecept. Spara ett läge först när du själv har provat spelet. Högst 20 profiler per spel. Profilinnehåll lagras lokalt i appens privata, icke säkerhetskopierade lagring; modellen får bara namnen, omfattningen och konkreta granskningsändringar. Miljövariabler, kontouppgifter, kommandoradsargument och enhetsmappningar kopieras inte till profilerna.

Återställningsprofiler kopierar **inte** spelinstallation, sparfiler, Windows-registret, godtyckliga INI-filer eller installerade runtimepaket. De återställer sparade val; avinstallerade Wine-/drivrutinspaket måste fortfarande finnas tillgängliga. Tidigare filredigering har kvar sin separata ångrafunktion. Ett pågående inställnings-, fil- eller modförsök måste behållas eller ångras före ett profilbyte. Ändrade berörda värden, modfiler, paketkällor eller saknade säkerhetskopior blockerar överskrivning och behåller återställningspunkten. En ofullständig native-modinstallation kan först behöva återställas i Modbibliotek och Nexus.

Modprofiler använder GameNatives granskade filplaceringar, ägarskap, hashkontroller och installationsjournaler. Första installationen av ett nytt modpaket granskas i befintlig modhantering. Denna profilväxling stöder granskade `OVERWRITE_COPY`-filer i spelmappen; särskilda mål, symlänkar och Bethesda-pluginladdordning hanteras i **Modbibliotek och Nexus**. Överlappande filer visar en möjlig konflikt, inte semantisk inkompatibilitet. När filordningen byggs om bevaras tidigare lagerbackuper med innehållshash under spelets privata modcache innan native-verktyget skapar nya korrekta lagerbackuper. Dessa återställningskopior tar lagringsutrymme och raderas inte av “Behåll profil”.

Spara, ändra och återställa profiler kräver avslutat spel. Det tidigare **Prova bryggan live / Ångra liveförsök** gäller fortsatt för stödda kontrollbryggeval. Global spelarplacering och Bluetooth-parkoppling ändras inte av profilverktygen. Läsverktygen injicerar inga kontrollsignaler. Ingen JNI-, SDL- eller inputbryggkod har ändrats i denna uppdatering.

### Bilder, installation och mätningar

Bilden tas med Android PixelCopy från spelets `SurfaceView`, aldrig hela appfönstret med chatt, inloggning eller tangentbord. Spelomgången kontrolleras före och efter. Längsta sida är högst 1600 pixlar, JPEG högst 2 MB. Bilder lagras inte i chattens historik, loggar eller galleri. Text som syns i själva spelet kan vara privat: granska bilden före sändning; den maskeras inte automatiskt. Det är en enstaka historisk bild, ingen kontinuerlig syn eller automatisk spelstyrning.

Integrationen använder fortfarande officiell ChatGPT-tokenbehörighet och Responses direkt från Android, med `store:false` och `stream:true`. Bildinmatning använder `input_image` med en JPEG-data-URL enligt [OpenAI:s bildguide](https://developers.openai.com/api/docs/guides/images-vision). [Token sharing-begränsningarna](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) tillåter bildinmatning när vald modell stöder den. Bildgenerering, generell datorstyrning och filuppladdnings-API ingår inte. Ingen separat API-debitering, extern server eller Codex app-server tillkommer. Stöd hos den aktuella kontomodellen och ett verkligt bildsvar behöver provas på enheten; modellnamnet ensamt är ingen verifiering.

ZIP/7z-importen använder den befintliga arkivextraktorn i en privat arbetsmapp. Tak: 20 GiB för arkiv och uppackat innehåll, 50 000 poster, begränsat djup. Delade/lösenordsskyddade arkiv och fristående MSI/ISO stöds inte. Avbruten eller felaktig uppackning publiceras inte som spel. Originalarkivet behålls. Förberedelse av en befintlig fungerande spelmiljö gör ingenting; en ofullständig miljö med befintliga filer får inte raderas automatiskt. Wine-guiden slutförs manuellt. Ett DLL-namn i en logg visar inte att en viss nedladdning är korrekt; appen installerar inga slumpmässiga DLL-filer eller runtimepaket från nätet.

Preflight är begränsad: högst 4 000 poster/djup 3 för snabb filkontroll och högst 3 000 spårade modfiler före vanlig biblioteksstart. Den kan inte upptäcka att sista BIN-delen saknas utan ett manifest. Historiska DLL-fel visas i assistentens kontroll; en gammal logg spärrar inte varje ny start. Debug- och externa startvägar omfattas inte av den automatiska Spela-kontrollen.

Stutterverktyget använder upp till 30 sekunders överlappande tvåsekundersfönster och bortser från chattöppna eller frame-generation-stride-förändrade fönster. P95-medel är inte p95 för hela spelpasset eller 1% low. Saknade sensorer förblir okända. Temperatur och FPS paras från samma fönster; samtidiga förändringar bevisar inte värmestrypning. Mät **samma scen i 60 sekunder** före och efter ett enskilt försök. Målen 30/40/60/90/120 FPS finns kvar; inget resultat eller 120 FPS utlovas.

### Testa på MagicPad

1. Avsluta spelet och installera uppdateringen ovanpå AI Dev. Starta Bloodstained med befintliga kontrollinställningar och verifiera input först.
2. Stäng spelet, spara en namngiven spelprofil, granska en liten ändring och återställ profilen. Prova **Ångra profil** även efter omstart av appen. Andra spel ska behålla sina inställningar.
3. Be Codex byta en knapp, granska, tillämpa, starta och prova. Stäng spelet och ångra. Home ska fortsatt öppna menyn. Kontrollens spelarplats ändras vid behov i den vanliga Controller-menyn.
4. Starta ett spel, bifoga en bild, förstora förhandsvisningen och fråga om ett synligt fel. Prova också att ta bort bilden och byta konversation. Ingen bild ska skickas före Skicka.
5. Importera ett eget mindre ZIP-/7z-spel med setup och sidofiler. Fortsätt till Codex, granska installeraren, slutför guiden och välj spelets EXE. Prova avbrott före publicering; originalet ska finnas kvar.
6. Spara modprofiler för två redan granskade paket och Original. Granska delade filer, byt prioritet, prova spelet och ångra.
7. Kör två jämförbara mätningar och be om analys. Kontrollera sensortillgång och bildtider; tolka inga syntetiska testvärden som uppmätt vinst på MagicPad.

### Verifiering av ai-dev.15

Slutverifieringen omfattar assistent-, profil-, kontroll-, modmaterialiserings-, arkiv-, återställningsjournal- och FPS-tester samt `assembleModernDebug`. Tester använder verkliga lokala konfigurations-/modfiler, Room och Androids UI via Robolectric; AI-svar, dokumentleverantör, sensorer och bildinnehåll är simulerade. Profilflödet har körts i 360 × 800 och 1280 × 800 och skärmbilderna granskats. De nya testen täcker namngiven lagring efter omstart, kopiering av kontrollprofiler utan ändring i andra spel, enskild mappning, modordning/Original/ångra för både ersatta och nya filer, externa filändringar även med moddar avstängda, arkivnamn/kollisioner/path traversal/avbrott, bildförhandsvisning och bildinmatning över flera verktygsomgångar. Kontonas befintliga inloggningsflöde ändras inte.

Resultat: **482 godkända tester, 0 fel, 1 överhoppat**. Det befintliga testet `ModTargetResolverTest.resolve_blocksAmbiguousExistingCaseVariants` kräver ett skiftlägeskänsligt filsystem och hoppar över på denna Mac. Bygget lyckades. Samtliga 32 native-bibliotek jämförs bytevis med ai-dev.14 vid paketering. Underlaget finns i `build/ai-dev/verification/game-care/`.

Ingen fysisk Android-enhet är ansluten. PixelCopy från verklig Vulkan/GL-spelyta, modellens verkliga bildsvar via kontot, native 7z, första Wine-förberedelsen och Windows-guider, spelens inputrespons och verkliga FPS/temperaturresultat är **inte enhetstestade här**. Inga uppmätta prestandavinster påstås. Bygginstruktionerna längre ned gäller fortsatt; använd `-PaiDev=true` och befintlig `app/keystores/ai-dev.keystore` vid uppdateringar.

## Uppdatering 2026-10-02: 120 FPS och lokala spelinstallationer

**1.2.1-ai-dev.14**, Android `versionCode=34`, utökar målvalet till **30, 40, 60, 90 och 120 FPS**. Valen radbryts i smala vyer. Befintliga spelmål och mätningar bevaras; valet av 120 FPS ändrar inte automatiskt begränsaren eller gör ett spel snabbare. Codex använder det valda målet och samma jämförelsemetod som i ai-dev.13. Exempeltexterna i chatten använder nu vanligt språk utan verktygsnamn.

### Installera ett offlinespel på MagicPad

1. Avsluta pågående spel och uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.14-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-34/GameNative-AI-Dev-1.2.1-ai-dev.14.apk) ovanpå AI Dev. Samma paket/signering; ingen avinstallation eller datarensning.
2. Öppna **Bibliotek → + → Installera offlinespel med Codex**. Samma val finns i assistentens **⋮**-meny. Tryck **Välj spelmapp** och välj hela mappen med exempelvis `setup.exe` och tillhörande `.bin`-filer, eller en redan uppackad spelmapp. Androids mappväljare ger åtkomst till just det materialet.
3. Håll appen öppen medan mappen kopieras. Originalet behålls. Kopian behöver eget lagringsutrymme, och installeraren kan dessutom kräva plats för det installerade spelet. Importen får en egen lokal spelidentitet och Steam-importinställningen gäller inte denna kopia.
4. Tryck **Fortsätt med Codex → Hjälp med installation och startfil**. Detta aktiverar spelåtkomst för frågan och skickar en analys genom den befintliga ChatGPT-anslutningen. Ingen modell används under själva kopieringen.
5. Codex undersöker tillgängliga Windows-EXE-filer och visar ett förslag. **Starta installeraren** sparar vald EXE och öppnar GameNatives vanliga Wine-start för just detta spel. Slutför Windows-guiden själv, gärna med installationsmappen `C:\Games` eller `A:\Installed`. Alla installationsfiler ligger tillsammans på A: med bevarade undermappar.
6. Stäng installeraren och öppna samma spels Codex-vy från biblioteket. Be den hitta det installerade spelet. Den kan undersöka spelmappen och spelets privata C:-enhet och föreslå en ny startfil. **Använd som startfil** sparar valet utan att köra spelet. Starta sedan från biblioteket och prova kontroll, bild och ljud innan optimeringsförsök.
7. **Ångra startfil** återställer tidigare startfil och startargument även efter appomstart. Den påverkar inte installerade filer eller registerändringar och är separat från befintlig konfigurations-/fil-/modbackup. En manuell ändring av startvalet stoppar överskrivning och bevarar återställningspunkten.

### Integrering och begränsningar

Importen återanvänder `CustomGameImporter` och `CustomGameScanner`. Den kopierar via SAF till en tillfällig mapp utanför bibliotekets sökrötter och publicerar först en färdig kopia. Biblioteket läser om listan när appen återupptas och de lokala spelmapparna har ändrats. Avbruten eller misslyckad kopiering tar bort den egna tillfälliga kopian och behåller originalet. Windows-kollisioner i filnamn och orimligt djupa/cykliska mappträd avvisas. En processdöd under kopiering kan lämna en tillfällig mapp; automatisk återupptagning av kopieringen ingår inte. Originalet är kvar och ofullständiga kopior registreras inte som spel.

`inspect_offline_installation` och `propose_offline_action` är bara tillgängliga för lokala spel med spelåtkomst. Filsökningen är begränsad till 20 000 poster, 80 kandidater och tio undermappsnivåer. Modellen ser filtrerade filnamn, storlek, arkitektur och lokalt utfärdade fil-ID:n. Filnamn behandlas som opålitliga uppgifter; inga EXE- eller BIN-bytes skickas till modellen. Endast x86/x64 PE-EXE stöds, inte DLL:er som döpts om. Inga modellskrivna sökvägar eller startargument tillåts. Länkar och sökvägar utanför just spelets rötter avvisas.

Förslaget är ett lokalt förhandsval: det varken kör eller skriver under AI-anropet. Vid godkännande verifieras filens SHA-256, filmetadata, konfigurationsversion och att ingen spelomgång körs. Sedan sparas återställningspunkten atomiskt före ändring av endast `executablePath` och `execArgs`. Befintliga startargument töms och detta visas på kortet. Den befintliga paketbundna launch-intenten återanvänds, utan temporära helkonfigurationsöverstyrningar. Android-aktiviteten för import är inte exporterad och inga nya behörigheter tillkommer.

Första stödet är **EXE-installationsmappar och färdiga spelmappar**. ZIP/7z behöver packas upp separat; fristående MSI, ISO-montering, automatiska knapptryckningar i Windows-guiden, hämtning av spel och obevakad installation ingår inte. En startad installerare är inte bevis på lyckad installation. Den befintliga Wine-miljön används; kompatibilitet och eventuella runtime-behov varierar per installerare. Spelfiler kan vara offline, men Codex-modellen kräver internet och använder samma befintliga ChatGPT-abonnemangsanslutning.

### Verifiering

Riktade tester täcker lokal EXE-upptäckt, avgränsning per spel, Windows PE-arkitektur, länkavvisning, färska fil-ID:n, ändrad EXE med oförändrad storlek/tidsstämpel, ändrad konfiguration, stoppkrav och hållbar återställning av enbart startvalet. En simulerad Android-dokumentleverantör testar riktig kopiering av EXE + BIN + undermapp, separat biblioteksidentitet, ingen radering av källan samt avbrott/fel utan publicerad delkopia. UI-tester i liggande och 360 dp stående läge kör riktiga agentverktyg med simulerade AI-svar: granskning → start-intent → upptäckt av installerad EXE → sparat startval → ångra. Kontrollinställningar och andra konfigurationsfält bevaras. 120 FPS-målet provas genom mätning, lagring och agentanalys i båda orienteringarna med syntetiska mätdata.

**257 tester godkända utan fel eller överhoppade tester**, samt lyckat APK-bygge. Fulla testresultat, APK-kontroller och granskade skärmbilder sparas under `build/ai-dev/verification/offline-install-120fps/`. Bygg-/testkommandot är samma som nedan, med assistent-, kontroll-, FPS- och konfigurationstester. Verklig Windows-installation, bibliotekets uppdatering efter återgång, spelstart från C: och faktisk 120 FPS på MagicPad kräver fysisk enhet; ingen sådan verifiering eller prestandavinst påstås av JVM-testerna.

## Uppdatering 2026-10-02: optimering per spel

**1.2.1-ai-dev.13**, Android `versionCode=33`, lägger till **Optimera** i varje spels assistent, både från biblioteket och i spelet. Samma flöde är tillgängligt för nyinstallerade spel från de källor som redan använder GameNatives gemensamma spelvy; inget separat AI-stöd behöver byggas per titel. Mål och mätningar delas inte mellan spel. Modellen anropas när användaren begär analys, inte vid import eller under spelmätningen.

Användaren rapporterade inför denna version att kontrollen åter fungerar i **Bloodstained**. Det är enhetsåterkoppling för det spelet, inte verifiering av alla spel. Denna uppdatering ändrar inte kontrollmappning, inputbrygga eller native-bibliotek. Vid prestandauppdrag instrueras agenten att bevara fungerande kontroll- och ljudinställningar.

### Arbetsgång på MagicPad

1. Avsluta spelet och uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.13-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-33/GameNative-AI-Dev-1.2.1-ai-dev.13.apk) ovanpå AI Dev. Samma paket och signerare; avinstallera inte.
2. Öppna ett installerat spels assistent och tryck **Optimera**. Välj **30, 40 eller 60 FPS**. Valet är ett analysmål och ändrar inte FPS-begränsaren. **Be Codex optimera** ger en första genomgång av aktuell konfiguration, emulator, tillgänglig hårdvaruinformation och tidigare mätningar. Spelåtkomst och ChatGPT-anslutning behövs för AI-analysen.
3. Starta spelet normalt. Gå till en återupprepningsbar scen/sparpunkt. Öppna **Quick Menu → Codex i spelet → Optimera**, ange scenen och tryck **Mät 60 sekunder**. Chatten stängs; fem sekunders nedräkning låter dig återgå till spelet. Spela samma runda i en minut. Den lilla panelen kan avbryta mätningen eller öppna resultatet när den är klar. Ingen AI-fråga skickas under mätningen.
4. Den första giltiga mätningen sparas som referens. Begär analys med **Be Codex optimera**. Agenten ska föreslå ett motiverat försök åt gången från den befintliga inställningskatalogen, eller stödda spelfiler när separat filåtkomst är aktiverad. Förslag tillämpas och ångras genom samma granskade flöde som tidigare. Sparade prestandaändringar kräver stoppat spel.
5. Starta med den ändrade konfigurationen och mät samma scen igen. Behåll spelversion, moddar, grafikval inne i spelet, ljusstyrka, laddning, energiläge och starttemperatur så lika som möjligt. Jämför FPS och bildtider och upprepa före slutsats. Välj att behålla eller ångra inställningsförsöket i chatten. **Använd senaste som ny referens** ändrar bara vilken mätning som jämförs; det tillämpar inga inställningar och tar inte bort inställningsbackupen.

Mätning kräver vanlig spelstart utan debug run/diagnostikläge eller aktiverad Wine/Box64-debugloggning. Om startkonfigurationen har ändrats krävs en ny spelstart för att resultatet ska kunna knytas till den. Detta är en guidad testcykel som fungerar helt på Android; inget separat datorsteg behövs. Automatiskt spelande, testloopar, global ändring av alla spel och automatisk hämtning av drivrutiner ingår inte.

### Mätmetod och gränser

Appens befintliga `PerformanceMetricsCollector` används; ingen extra renderings- eller inputslinga införs. Den mäter normalt ett tvåsekundersfönster var 500 ms. Optimeringsmätningen väljer fönster med minst två sekunders avstånd på monoton klocka och börjar först när nedräkningen och ett helt nytt fönster passerat. Högst 30 fönster sparas som sammanfattning. Minst 27 giltiga fönster krävs, motsvarande 54 av 60 sekunders underlag. UI visar faktisk täckning, FPS-fönstersnitt och **medelvärdet av fönstrens p95**. Det är inte hela minutens p95 eller ett beräknat ”1% low”.

Paus, bakgrund, återöppnad chatt, stängd spelvy och avslutad spelomgång avbryter mätningen. Ofullständiga resultat behålls med orsak och används inte som godkänd referens. Föråldrade/ogiltiga värden och data från andra spelomgångar avvisas. Bildgenerering eller annan frame-stride än 1 gör resultatet olämpligt för denna jämförelse. Frånvarande sensorer rapporteras som okända, inte som noll belastning; CPU-belastningen kan avse hela enheten.

Startkonfigurationen fångas efter att startkomponenterna installerat/sparat runtime-metadata. En kanonisk SHA-256 identifierar konfigurationen utan att skicka privata miljövärden; ändrad konfiguration upptäcks före och efter mätning. Sessionsstatistik och namn påverkar inte identiteten. Sammanfattningarna innehåller bara filtrerade inställningar, numeriska mätdata och användarens filtrerade scenbeskrivning. Atomiskt skrivna filer ligger i appens privata `noBackupFilesDir/assistant/optimization/`, högst åtta försök per spel inklusive vald referens. Mål, referens och mätningar överlever en normal APK-uppdatering.

Jämförelsen kräver samma mål, scenetikett, enhet, appversion, Android-version, skärmuppdatering, laddnings-/strömläge och observerad strömprofil. Kända skillnader i starttemperatur över fem grader flaggas. Saknade sensorer, ändringar inne i PC-spelet, modfiler, spelversion, ljusstyrka och exakt spelad runda verifieras inte automatiskt. Matchande metadata är därför villkorligt underlag, inte bevis på orsak eller en garanterad FPS-vinst. Ny appversion kräver nya referensdata för direkt jämförelse.

### Codex-integrering och verifiering

`read_optimization_context` läser spelmålet, konfiguration och tillåtna inställningar, SoC/RAM-information, mätningar, jämförelsens begränsningar och tillgänglig livedata i ett verktygsanrop. Det uppfyller kravet på konfigurationsläsning före `propose_settings`. Inställningsläsningar öppnar nu inte gamla loggarkiv i onödan. Modellen ska skilja Box64 från FEX, inte gissa installerade drivrutiner, och inte kalla ett resultat bättre när mätningar saknas eller inte kan jämföras. Befintlig tillämpning, backup och återställning återanvänds.

**244 tester godkända, inga fel eller överhoppade tester**, samt lyckat APK-bygge. Nya tester omfattar tidsgränser, mätfönster, ogiltiga data, avbrott, konfigurationsidentitet, spelisolering, begränsad historik och jämförelsekrav. Robolectric kör den riktiga anslutningen från `LiveGameSession.metrics` till privat resultatlagring. UI-tester i båda orienteringarna kör målval → lokal mätning → sparad referens → tre simulerade modellomgångar → granskat förslag; inga AI-anrop görs före användarens analys och kontrollkonfigurationens bytes förblir oförändrade. Skärmbilderna är granskade.

Resultat sparas under `build/ai-dev/verification/game-optimization/`. Mätvärdena i tester och skärmbilder är syntetiska. Verklig frametidsinsamling och eventuella förbättringar i Bloodstained eller andra MagicPad-spel är **inte uppmätta i denna byggmiljö**. Molnagentens kvalitet och faktiska svarstid kräver fortsatt prov på enheten. Nästa utvidgning bör utgå från dessa spelbundna mätningar: biblioteksöversikt, fler säkert validerade runtime-/grafikalternativ och bättre koppling till spelets egna grafikfiler, med samma granskning och ångra.

## Uppdatering 2026-10-02: se inputens signalväg och analysera den med Codex

**1.2.1-ai-dev.12**, Android `versionCode=32`, lägger till **Visa input live** under **Quick Menu → Codex i spelet → Kontroll och input**. Den lilla panelen visar de senaste Android-signalerna, valda profilmappningar, skrivningar till spelarplatsens kontrollbrygga och om native-väckningen ändrade sekvensräknaren. Varje steg har en ålder. Panelen kan minimeras och avslutas med **Avsluta och granska**. Det tidigare 20-sekunderstestet finns kvar.

Visningen är en lokal avläsning. Den injicerar ingen input, byter inga profiler och ändrar inga anslutningsflaggor eller inställningar. Den stoppar senast efter fem minuter, vid återgång till chatten, när appen går i bakgrunden eller när spelomgången slutar. Bara kontrollhändelser registreras; inga skrivna texter, skärmbilder, kontrolladresser eller inloggningsuppgifter. Historiken är begränsad till 100 händelser, 256 signalintervall, åtta kontroller och 16 äldre Wine-klienter. Ny spelomgång tömmer observationen. Öppna chatten och tryck **Analysera input med Codex** för att skicka frågan med spelåtkomst. Inget automatiskt AI-anrop sker när panelen startar, uppdateras eller stannar.

**Visa destinationer och API** visar en avläsning vid öppnandet: per spelarplats visas den relativa delade minnesfilen, anslutning, buffertstatus, native-kontroll, knappbitar och kodade axelvärden. Under observationen registreras även äldre Wine-klienters XInput/DirectInput-begäran, rapporterat process-ID, lokal UDP-port, sända tillståndspaket och sändningsfel. Process-ID:t är klientens egen uppgift, inte en verifierad identifiering av spelets process. En lyckad sändning är inget mottagningskvitto. SDL/evshim kan läsa delat minne utan sådana äldre anrop, så noll klienter är inte ett bevis på fel.

Agentens nya `read_input_route` samlar aktuell konfiguration, tillåtna inställningar, anslutna kontroller, mappningar, bryggans aktuella data och senaste observation i ett verktygsanrop. Lästillfället binds till spelomgången; byte av omgång under avläsningen avvisas. Verktyget uppfyller samma krav på konfigurationsläsning före ett förslag som `read_configuration`, med oförändrad granskning och ångra. Agenten instrueras att börja här vid kontrollproblem och undvika att läsa samma underlag tre gånger. Det simulerade UI-flödet läser, föreslår och svarar i tre modellomgångar i stället för fem; faktisk molnlatens har inte mätts.

Avläsningarna visar hur långt input observerats i appen. Händelserna är separata observationer, inte en koppling av samma knapptryckning genom varje steg. Delat minne kan ändras under läsningen. Ett lyckat native-anrop, sekvensökning eller skrivning bevisar fortfarande inte att SDL/Wine eller PC-spelet har behandlat knappen. Inget stöd för läsning av spelets laddade DLL:er eller bekräftelse för varje konsumerad knapp läggs till.

### Uppdatera och prova på MagicPad

1. Avsluta spelet och uppdatera via **⋮ → Appuppdateringar**, eller installera [ai-dev.12-APK:n](https://github.com/stenerstrom/GameNative/releases/download/ai-dev-32/GameNative-AI-Dev-1.2.1-ai-dev.12.apk) ovanpå AI Dev. Samma paket och signerare används; avinstallera inte och rensa inte data. Rättningarna i ai-dev.11 följer med.
2. Starta samma spel med befintliga inställningar. Öppna **Quick Menu → Codex i spelet → Kontroll och input → Visa input live**. Tryck A/B, styrkors, båda spakar och triggers. Kontrollera också om spelet reagerar. Ingen debug run krävs.
3. Prova att minimera panelen. Input ska fortsätta fungera. Avsluta och granska; jämför spelarplats, mappning, signalernas ålder och destinationer. En profil som mappar till tangentbord/mus behöver inte ge en gamepad-skrivning.
4. Tryck **Analysera input med Codex** med spelåtkomst aktiverad. Be om ett relevant nästa steg. Verifiera att förslag kräver din tillämpning, att liveförsök går att ångra och att ingen sparad inställning ändras av själva observationen.
5. Prova bakgrund/återgång, avbruten observation och nästa spel. Kontrollens knappsläpp får inte försvinna och gamla observationer får inte presenteras som input från den nya spelomgången.

**231 tester godkända, inga fel eller överhoppade tester**, och `assembleModernDebug` lyckades. Tester kör riktiga mappningar och Java-minnesfiler med JNI-väckningen ersatt av en testgräns: upprepade avläsningar ändrar varken bytes, buffertposition, anslutning eller antal native-anrop; knappsläpp fungerar efter stoppad observation. Livevisningens tidsgräns, åldrar, begränsningar, filtrering och bindning till rätt spelomgång kontrolleras. UI-flödena kör både kort test och livevisning i stående och liggande läge, start/minimering/stopp, explicit analys med tre simulerade modellomgångar och granskat förslag utan sparad ändring. Skärmbilderna är granskade. Samtliga 32 paketerade native-bibliotek är byteidentiska med ai-dev.11.

Lokala resultat sparas under `build/ai-dev/verification/input-route/`. Verklig Bluetooth/USB, JNI, SDL/Wine, spelets respons och molnsvarets kvalitet behöver verifieras på MagicPad; ingen fysisk Android-enhet är ansluten till byggmiljön.

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
