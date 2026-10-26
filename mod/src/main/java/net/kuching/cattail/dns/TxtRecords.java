package net.kuching.cattail.dns;

import java.io.IOException;
import java.util.List;

/** Source of DNS TXT records for a host name. */
public interface TxtRecords {

    /**
     * Returns every TXT value of {@code host} (surrounding quotes stripped), or an empty list
     * when the name has none.
     *
     * @throws IOException when the lookup itself fails (timeout, SERVFAIL, NXDOMAIN, ...)
     */
    List<String> lookup(String host) throws IOException;
}
