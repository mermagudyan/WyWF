import com.google.gson.Gson;
import java.io.FileReader;
import java.util.Map;

public class JsonCheck {
    public static void main(String[] a) throws Exception {
        Gson g = new Gson();
        for (String f : new String[]{"src/main/resources/assets/wywf/lang/en_us.json",
                                      "src/main/resources/assets/wywf/lang/ru_ru.json"}) {
            try {
                Map<?,?> m = g.fromJson(new FileReader(f), Map.class);
                System.out.println(f + " OK keys=" + m.size());
            } catch (Exception e) {
                System.out.println(f + " ERR " + e);
            }
        }
    }
}
