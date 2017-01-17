package net.xtrafrancyz.bwb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author xtrafrancyz
 */
public class Application implements HttpHandler, Runnable {
    public List<Bungee> bungies = null;
    public Config config;
    
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        String host;
        List<Bungee> local = bungies;
        if (local == null || local.isEmpty()) {
            host = config._default;
        } else {
            Iterator<Bungee> it = local.iterator();
            Bungee min = it.next();
            while (it.hasNext()) {
                Bungee next = it.next();
                if (min.online > next.online)
                    min = next;
            }
            min.online++;
            host = min.host;
        }
        exchange.getResponseSender().send(host);
    }
    
    @Override
    public void run() {
        while (true) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(config.checkUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestProperty("User-Agent", "Bungee Web Balancer");
                conn.setDoInput(true);
                
                BufferedReader in = null;
                try {
                    InputStream is;
                    if (conn.getResponseCode() > 400)
                        is = conn.getErrorStream();
                    else
                        is = conn.getInputStream();
                    in = new BufferedReader(new InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null)
                        sb.append(line);
                    
                    JsonObject json = new JsonParser().parse(sb.toString()).getAsJsonObject();
                    JsonArray arr = json.getAsJsonArray("bungee");
                    List<Bungee> newList = new ArrayList<>(arr.size());
                    for (JsonElement elem : arr) {
                        JsonObject object = elem.getAsJsonObject();
                        Bungee b = new Bungee();
                        b.host = object.get("host").getAsString();
                        b.online = Integer.parseInt(object.get("players").getAsString().split("/")[0]);
                        newList.add(b);
                    }
                    bungies = newList;
                } finally {
                    if (in != null)
                        in.close();
                    conn.disconnect();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                bungies = null;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }
    
    private boolean readConfig() throws IOException {
        File confFile = new File("config.json");
        if (!confFile.exists()) {
            this.config = new Config();
            JsonWriter writer = new JsonWriter(new FileWriter(confFile));
            writer.setIndent("  ");
            writer.setHtmlSafe(false);
            new Gson().toJson(config, Config.class, writer);
            writer.close();
            System.out.println("Created config.json");
            return false;
        } else {
            this.config = new Gson().fromJson(
                Files.readAllLines(confFile.toPath()).stream()
                    .map(String::trim)
                    .filter(s -> !s.startsWith("#") && !s.isEmpty())
                    .reduce((a, b) -> a += b)
                    .orElse(""),
                Config.class
            );
            return true;
        }
    }
    
    public static void main(String[] args) {
        Application instance = new Application();
        
        try {
            if (!instance.readConfig())
                System.out.println("Fill config.json file");
        } catch (Exception ex) {
            ex.printStackTrace();
            return;
        }
        
        Undertow.builder()
            .addHttpListener(instance.config.port, instance.config.host)
            .setHandler(instance)
            .build()
            .start();
        
        Thread updater = new Thread(instance, "Online Updater");
        updater.setDaemon(true);
        updater.start();
    }
}
