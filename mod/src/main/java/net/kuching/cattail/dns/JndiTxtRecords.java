package net.kuching.cattail.dns;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** TXT lookup through the JDK's JNDI DNS provider, using the system resolver. */
public final class JndiTxtRecords implements TxtRecords {

    @Override
    public List<String> lookup(String host) throws IOException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(host, new String[] {"TXT"});
            List<String> out = new ArrayList<>();
            Attribute txt = attrs.get("TXT");
            if (txt != null) {
                NamingEnumeration<?> values = txt.getAll();
                while (values.hasMore()) {
                    out.add(stripQuotes(String.valueOf(values.next())));
                }
            }
            return out;
        } catch (NamingException e) {
            throw new IOException("TXT lookup failed for " + host, e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
