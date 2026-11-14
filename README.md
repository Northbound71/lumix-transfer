# Lumix Transfer

Enkel Android-app til at forbinde til et Panasonic Lumix-kamera over Wi-Fi og hente billeder og video.

## Sådan bruges den
1. Slå Wi-Fi til på kameraet (Wi-Fi-menuen, "Ny forbindelse" / fjernbetjening).
2. Forbind telefonen til kameraets Wi-Fi i Android-indstillinger. Hvis Android spørger, om forbindelsen skal beholdes selvom der ikke er internet, vælg *Behold*.
3. Åbn Lumix Transfer og tryk **Forbind**. Appen finder selv kameraet (standard 192.168.54.1, ellers søger den på netværket).
4. Vælg filer (tryk på dem, eller **Vælg alle** / **Vælg nye**) og tryk **Hent valgte**.
5. Filerne gemmes i Galleri i mappen *Lumix* (andre filtyper i Download/Lumix).

Hold skærmen tændt under overførsel (appen gør det automatisk). Hvis noget fejler, tryk **Kopiér log** og send loggen.

## Bygning
Projektet bygges automatisk af GitHub Actions (`.github/workflows/build.yml`).
Efter hver push: Actions → seneste kørsel → Artifacts → `lumix-transfer-apk`.
