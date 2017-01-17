package net.xtrafrancyz.bwb;

import java.util.List;

/**
 * @author xtrafrancyz
 */
public class Host {
    public long expire;
    
    public String hostname;
    public List<String> ips;
    public int accessesCounter = 0;
    
    public Host(String hostname, long expireMillis) {
        this.hostname = hostname;
        this.expire = System.currentTimeMillis() + expireMillis;
    }
}
