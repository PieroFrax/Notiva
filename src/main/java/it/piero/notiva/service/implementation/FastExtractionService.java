package it.piero.notiva.service.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.piero.notiva.model.DocUnitRequest;
import it.piero.notiva.model.ExtractionResult;
import it.piero.notiva.utils.LogProbsUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi.LogProbs;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FastExtractionService {

    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final LogProbsUtils logProbsUtils;

    public FastExtractionService(ChatClient.Builder chatClient, ObjectMapper mapper, LogProbsUtils logProbsUtils) {
        this.chatClient = chatClient.build();
        this.mapper = mapper;
        this.logProbsUtils = logProbsUtils;
    }

    private static final String SYSTEM = """
        Sei un estrattore deterministico.

        Riceverai dall'utente UN JSON con struttura:
        {
          "records": [ ... ],   // blocchi TEXT/TABLE normalizzati (OCR)
          "fields":  [ ... ],   // nomi dei campi da estrarre (stringhe libere)
          "notes":   "<opzionale>"
        }

        PRINCIPI
        - Usa SOLO ciò che c’è in "records". Non inventare.
        - Considera l’ordine naturale dei record e la pagina; priorità a region=BODY. Ignora FOOTER salvo pertinenza evidente.
        - Risultato deterministico: a parità di condizioni, scegli sempre lo stesso candidato.
        - Output: SOLO JSON con lo schema in fondo, niente testo extra.

        RICOSTRUZIONE DAI RECORD
        1) TEXT: usa il testo così com’è.
        2) TABLE (tutte le tabelle, nessun nome colonna fisso)
           - Se la prima riga appare da intestazione, usala per comprendere le colonne; in ogni caso considera l’intera tabella.
           - Stima dinamicamente colonna-chiave (etichette) vs colonne-valore:
             • chiave: stringhe più brevi, meno cifre, alta varietà;
             • valore: testo più lungo e/o cifre (importi, codici, descrizioni).
           - Per ogni riga:
             • se la cella chiave è valorizzata ⇒ nuovo blocco {key}; valore = CONCAT di tutte le colonne non-chiave non vuote (ordine tabella, separatore " | ").
             • se la cella chiave è NULLA ⇒ riga di CONTINUAZIONE del blocco precedente: APPENDI il testo delle colonne non-chiave (separatore " | ").
           - All’interno dei valori rileva anche eventuali coppie “Etichetta: Valore”
             (es. “Totale: …”, “Percentuale: …”, “Scadenza: …”, “Telefono: …”) e usale come candidati AGGIUNTIVI senza perdere il testo originale.
           - Evita duplicazioni quando concateni.

        SELEZIONE, AMBIGUITÀ, ALTERNATIVE E CONFIDENCE
        - Normalizza i confronti (case-insensitive; ignora accenti e punteggiatura; spazi/underscore equivalenti).
        - Ranking candidati (in ordine):
          1) Etichette (key) con maggiore overlap di token/substring col nome del campo.
          2) “Etichetta: valore” dentro i valori se semanticamente vicine al campo.
          3) Porzioni di testo nei valori concatenati che contengano i token del campo.
        - Se c’è un chiaro vincitore ⇒ status="OK", "alternatives":[]. Imposta "confidence" in [0.0,1.0] (due decimali).
        - Se sei **indeciso** (più candidati plausibili):
          • scegli comunque il **più probabile** come "value";
          • imposta status="AMBIGUO";
          • compila "alternatives" con le altre opzioni plausibili (max 3), ordinate per probabilità discendente;
          • "confidence" è quella del candidato scelto.
        - Se nessun candidato valido ⇒ status="NOT_FOUND", value="NOT_FOUND", confidence=0.0, alternatives=[].

        CALIBRAZIONE "confidence" (applica i cap sotto, poi arrotonda a 2 decimali)
        - Base: parti da 0.50 per un candidato plausibile.
        - Bonus:
          +0.25 se esiste un match diretto ETICHETTA≈campo (overlap token alto) e il valore è adiacente.
          +0.15 se è una coppia esplicita "Etichetta: Valore".
        - Penalità:
          -0.20 se ci sono ≥2 candidati entro un margine stretto di pertinenza (quasi equivalenti).
          -0.15 se il valore richiede normalizzazioni pesanti (es. importo con molto rumore testuale).
          -0.10 se proviene da TEXT generico anziché da tabella/etichetta chiara.
        - Cap rigidi:
          Se status="AMBIGUO" ⇒ confidence ≤ 0.65.
          Se il match è solo substring debole ⇒ confidence ≤ 0.60.
          Se campo monetario ma il valore non è un importo “pulito” ⇒ confidence ≤ 0.40.
          Se “NOT_FOUND” ⇒ confidence = 0.00.
        - Range interpretazione finale:
          0.90–1.00 solo per etichetta ≈ campo + valore adiacente e unici (senza rivali forti).
          0.70–0.89 per evidenza robusta ma con piccoli dubbi/rumore.
          0.50–0.69 per match moderati o da TEXT non strutturato.

        VALIDAZIONI E NORMALIZZAZIONI (GENERICO)
        - Importi monetari:
          • Un valore è NON valido se è solo “€” o non contiene cifre.
          • Per default, **se il campo richiesto fa riferimento a un importo** (token come: "importo", "totale", "canone", "acconto",
            "saldo", "lordo", "netto", "iva", "imponibile", "caparra", oppure il candidato è chiaramente monetario):
              - Estrai SOLO l’importo/le cifre monetarie (nessuna parola/etichetta).
              - **Formatta in it-IT**: `€ 12.345,67` (spazio dopo simbolo, punto per migliaia, virgola per due decimali).
              - Se la valuta è diversa (USD/GBP/CHF/...), mantieni simbolo/codice corretti e NON forzare l’euro.
              - Se la valuta non è esplicita, ASSUMI EUR e anteponi `€ `.
              - Se per lo stesso campo esistono **più importi pertinenti** (es. breakdown/suddivisione), restituisci **solo gli importi**, separati da ` | ` (nessuna etichetta).
          • **ECCEZIONE (campi descrittivi di importi)**: se il nome del campo indica esplicitamente che vuoi anche la **descrizione/etichetta** oltre agli importi
            (token come: "descrizione", "dettaglio", "composizione", "ripartizione", "suddivisione", "breakdown", "voci", "items"):
              - Includi **sia** gli importi **sia** il testo descrittivo strettamente pertinente (etichette come "A misura", "A canone", parti dopo "Di cui:", note sintetiche).
              - Formato consigliato per coppie: `Etichetta: € 1.234,56`. Per più coppie: separa con ` | ` (es.: `A misura: € 5.600,00 | A canone: € 1.850,00`).
              - Se non c’è etichetta ma c’è testo descrittivo breve pertinente, usa `€ 1.234,56 – <testo>`. Evita frasi lunghe.
              - Le **alternative** seguono la stessa regola (se il campo è descrittivo, anche le alternative includono etichetta+importo; altrimenti solo importi).
        - Date: preferisci stringhe che assomigliano a date (2024-06-25, 25/06/2024). Mantieni formato coerente anche nelle alternative.
        - Codici/ID: preferisci stringhe alfanumeriche compatte. Le alternative vanno normalizzate allo stesso criterio.
        - Liste/elenco: se il campo richiesto suggerisce pluralità (es. “lista”, “elenco”, “items”), restituisci elementi separati da “ | ”, nell’ordine in cui compaiono.

        EVIDENCE
        - Per ogni estrazione fornisci "snippet" tratto dal testo sorgente su cui ti sei basato (può includere l’etichetta).
        - Per le alternative **non** includere snippet: solo i valori normalizzati.

        OUTPUT (SOLO JSON, schema esatto):
        {
          "extractions":[
            {
              "name":"<campo_richiesto>",
              "value":"<string|NOT_FOUND>",
              "evidence":{"snippet":"<string>"},
              "status":"OK"|"NOT_FOUND"|"AMBIGUO",
              "confidence": <numero tra 0.0 e 1.0>,
              "alternatives":[ "<string>", ... ]   // presente sempre, eventualmente vuoto
            }
          ]
        }

        ***LISTA ISTRUZIONI CON MASSIMA PRIORITÀ**
        [istruzioni]
        """;

    public ExtractionResult extract(DocUnitRequest request) {


        Resource userResource = toResource(request);

        String systemPrompt = SYSTEM.replace("[istruzioni]", request.getNotes());

        ChatResponse call = chatClient
                .prompt()
                .system(systemPrompt)
                .user(userResource)
                .call()
                .chatResponse();

        String output = call.getResult().getOutput().getText();

        ExtractionResult result;
        try {
            result = mapper.readValue(output, ExtractionResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Parse JSON fallito. Output:\n" + output, e);
        }

        LogProbs lp = LogProbsUtils.asLogProbs(call.getResult().getMetadata().get("logprobs"), mapper);
        if (lp == null || result.getExtractions() == null || result.getExtractions().isEmpty()) {
            return result;
        }

        List<LogProbsUtils.Range> windows = LogProbsUtils.jsonStringValueWindows(lp, "value");

        var toks = LogProbsUtils.content(lp);
        var full = LogProbsUtils.text(lp);

        int n = toks.size();
        int[] start = new int[n];
        int[] end   = new int[n];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            start[i] = pos;
            pos += toks.get(i).token().length();
            end[i] = pos;
        }

        int fromChar = 0;

        for (var item : result.getExtractions()) {
            String v = item.getValue();

            if (v == null || "NOT_FOUND".equals(v)) {
                item.setConfidence(0.0);
                continue;
            }

            String pattern = "\"value\"\\s*:\\s*\"" + java.util.regex.Pattern.quote(v) + "\"";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL)
                    .matcher(full);

            if (!m.find(fromChar)) {
                continue;
            }

            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("\"value\"\\s*:\\s*\"(" + java.util.regex.Pattern.quote(v) + ")\"",
                            java.util.regex.Pattern.DOTALL)
                    .matcher(full);

            if (!m2.find(fromChar)) {
                continue;
            }

            int valStartChar = m2.start(1);
            int valEndChar   = m2.end(1);
            fromChar = valEndChar;

            int sTok = charToTokenIndex(start, end, valStartChar);
            int eTok = charToTokenIndexEnd(start, end, valEndChar);

            if (sTok >= 0 && eTok > sTok) {
                var range = new LogProbsUtils.Range(sTok, eTok);

                double confStrict = LogProbsUtils.windowConfidenceStrictTuned(
                        toks, range,
                        1.8,
                        2,
                        3,
                        0.6
                );

                if (!Double.isNaN(confStrict)) {
                    item.setConfidence(confStrict);
                }
            }
        }


        return result;
    }

    private static int charToTokenIndex(int[] start, int[] end, int charPos) {
        for (int i = 0; i < start.length; i++) {
            if (charPos >= start[i] && charPos < end[i]) return i;
        }
        return -1;
    }

    private static int charToTokenIndexEnd(int[] start, int[] end, int charPos) {
        for (int i = 0; i < start.length; i++) {
            if (end[i] >= charPos) return i + 1;
        }
        return start.length;
    }


    private Resource toResource(Object o) {
        try {
            return new ByteArrayResource(mapper.writeValueAsBytes(o));
        } catch (Exception e) {
            throw new RuntimeException("Serializzazione JSON fallita", e);
        }
    }

}
