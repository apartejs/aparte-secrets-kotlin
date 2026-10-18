package dev.apartejs.secrets.androidtest;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.apartejs.secrets.Secrets;
import dev.apartejs.secrets.Span;

/**
 * The equality test, on Android: the model loads from the library's jar as an app would load it, and
 * the reference texts (downloaded from Hugging Face as a test asset by CI) must give exactly the spans
 * of the Python and JavaScript runtimes. Written in Java on purpose: it also checks that the API reads
 * well from Java.
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceParityTest {
    @Test
    public void givesTheReferenceSpansOnAndroid() throws Exception {
        Secrets secrets = Secrets.load();
        List<String> bad = new ArrayList<>();
        int texts = 0;
        long t0 = System.nanoTime();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("references_int8.jsonl"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                String text = o.getString("text");
                JSONArray ref = o.getJSONArray("spans");
                List<Span> mine = secrets.detect(text);
                boolean same = mine.size() == ref.length();
                for (int i = 0; same && i < ref.length(); i++) {
                    JSONObject s = ref.getJSONObject(i);
                    // Python offsets count code points; String.codePoints() needs Android 7, offsetByCodePoints does not
                    String value = text.substring(text.offsetByCodePoints(0, s.getInt("start")), text.offsetByCodePoints(0, s.getInt("end")));
                    same = mine.get(i).getText().equals(value) && mine.get(i).getSubtype().equals(s.getString("subtype"))
                            && Math.abs(mine.get(i).getScore() - s.getDouble("score")) <= 2e-4;
                }
                if (!same && bad.size() < 10) bad.add(text.substring(0, Math.min(60, text.length())));
                texts++;
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        Log.i("AparteSecrets", texts + " texts, " + String.format("%.1f", ms / texts) + " ms per text, " + bad.size() + " differ");
        assertTrue("no reference texts", texts > 0);
        assertTrue(bad.size() + " texts differ:\n" + bad, bad.isEmpty());
    }
}
