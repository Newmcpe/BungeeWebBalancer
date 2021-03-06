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
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author xtrafrancyz
 */
public class Application implements HttpHandler, Runnable {
    public Map<String, Host> hostnameCache = new HashMap<>();
    public List<Bungee> bungees = null;
    public Config config;
    
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        String host;
        List<Bungee> local = bungees;
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
                        b.id = object.get("id").getAsString();
                        
                        if (config.ignoredBungees.contains(b.id))
                            continue;
                        
                        b.host = object.get("host").getAsString();
                        b.online = Integer.parseInt(object.get("players").getAsString().split("/")[0]);
                        
                        String hostname;
                        String port = "";
                        String[] split = b.host.split(":");
                        if (split.length == 2) {
                            hostname = split[0];
                            if (!split[1].equals("25565"))
                                port = ":" + split[1];
                        } else {
                            hostname = split[0];
                        }
                        
                        Host host = hostnameCache.get(hostname);
                        if (host == null || host.expire < System.currentTimeMillis())
                            host = new Host(hostname, 10 * 60 * 1000);
                        
                        if (host.ips == null) {
                            host.ips = new ArrayList<>();
                            
                            try {
                                /*
                                Hashtable<String, String> env = new Hashtable<>();
                                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                                env.put("java.naming.provider.url", "dns:");
                                env.put("com.sun.jndi.dns.timeout.retries", "1");
                                DirContext ictx = new InitialDirContext(env);
                                Attributes response = ictx.getAttributes(hostname, new String[]{"A"});
                                Attribute a = response.get("a");
                                for (int i = 0; i < a.size(); i++)
                                    host.ips.add((String) a.get(i));
                                */
                                
                                for (InetAddress addr : InetAddress.getAllByName(hostname))
                                    host.ips.add(addr.getHostAddress());
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                host.ips.add(hostname);
                            }
                            
                            hostnameCache.put(hostname, host);
                        }
                        if (!host.ips.isEmpty()) {
                            b.host = host.ips.get(host.accessesCounter % host.ips.size()) + port;
                            host.accessesCounter++;
                        }
                        
                        newList.add(b);
                    }
                    bungees = newList;
                } finally {
                    if (in != null)
                        in.close();
                    conn.disconnect();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                bungees = null;
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
