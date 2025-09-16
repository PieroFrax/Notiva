package it.piero.notiva.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.openai.api.OpenAiApi.LogProbs;
import org.springframework.ai.openai.api.OpenAiApi.LogProbs.Content;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public final class LogProbsUtils {

    private LogProbsUtils() {}


    public record Range(int start, int end) {}

    public static LogProbs asLogProbs(Object raw, ObjectMapper mapper) {
        if (raw == null) return null;
        if (raw instanceof LogProbs lp) return lp;
        try {
            return mapper.convertValue(raw, LogProbs.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String text(LogProbs lp) {
        if (lp == null || lp.content() == null) return "";
        return lp.content().stream().map(Content::token).collect(Collectors.joining());
    }

    public static List<Content> content(LogProbs lp) {
        return (lp != null && lp.content() != null) ? lp.content() : List.of();
    }

    public static List<Range> jsonStringValueWindows(LogProbs lp, String fieldName) {
        List<Content> toks = content(lp);
        String fullText = text(lp);

        int n = toks.size();
        int[] start = new int[n];
        int[] end   = new int[n];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            start[i] = pos;
            pos += toks.get(i).token().length();
            end[i] = pos;
        }

        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher m = p.matcher(fullText);

        List<Range> windows = new ArrayList<>();
        while (m.find()) {
            int valStartChar = m.start(1);
            int valEndChar   = m.end(1);
            int s = charToTokenIndex(start, end, valStartChar);
            int e = charToTokenIndexEnd(start, end, valEndChar);
            if (s >= 0 && e > s) windows.add(new Range(s, e));
        }
        return windows;
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


    private static final java.util.regex.Pattern CONTENT_CHARS =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}]");

    private static boolean isContentToken(String t) {
        return t != null && CONTENT_CHARS.matcher(t).find();
    }

    private static int countContentTokens(List<Content> content, Range r) {
        if (content == null || r == null) return 0;
        int cnt = 0;
        int end = Math.min(r.end(), content.size());
        for (int i = r.start(); i < end; i++) {
            Content c = content.get(i);
            if (isContentToken(c.token())) {
                double lp = c.logprob();
                if (!(lp == 0.0 || Double.isNaN(lp) || Double.isInfinite(lp))) {
                    cnt++;
                }
            }
        }
        return cnt;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return Double.NaN;
        return Math.max(0.0, Math.min(1.0, v));
    }

    public static double windowConfidenceMarginGeometricContentOnly(List<Content> content, Range r) {
        if (content == null || r == null) return Double.NaN;

        double sumLog = 0.0;
        int used = 0;
        int end = Math.min(r.end(), content.size());

        for (int i = r.start(); i < end; i++) {
            Content c = content.get(i);
            String tok = c.token();
            double lp = c.logprob();

            if (!isContentToken(tok) || lp == 0.0 || Double.isNaN(lp) || Double.isInfinite(lp)) {
                continue;
            }

            double p = Math.exp(lp);
            double p2 = 0.0;

            var alts = c.topLogprobs();
            if (alts != null && !alts.isEmpty()) {
                for (var alt : alts) {
                    if (tok.equals(alt.token())) continue;
                    double q = Math.exp(alt.logprob());
                    if (q > p2) p2 = q;
                }
            }

            double ratio = (p2 > 0.0) ? (p / (p + p2)) : p;

            ratio = Math.max(1e-9, Math.min(1.0 - 1e-9, ratio));

            sumLog += Math.log(ratio);
            used++;
        }

        if (used == 0) return Double.NaN;
        return Math.exp(sumLog / used);
    }


    public static double windowConfidenceStrictTuned(
            List<Content> content,
            Range r,
            double gamma,
            int k,
            int minContent,
            double floorFactor
    ) {
        double gm = windowConfidenceMarginGeometricContentOnly(content, r);
        if (Double.isNaN(gm)) return Double.NaN;

        int L = countContentTokens(content, r);
        if (L < minContent) {

            return clamp01(gm * 0.8);
        }

        double lengthPenalty = (double) L / (L + Math.max(1, k));
        double sharpened = Math.pow(Math.max(1e-9, gm), Math.max(1.0, gamma));
        double strict = clamp01(sharpened * lengthPenalty);

        double floor = clamp01(gm * Math.max(0.0, Math.min(1.0, floorFactor)));
        return Math.max(strict, floor);
    }

}
