package com.vexsoftware.votifier.support.forwarding;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

public class ServerFilter {

    public ServerFilter(Collection<String> names, boolean whitelist) {
        this.names = Collections.unmodifiableSet(new HashSet<>(names));
        this.whitelist = whitelist;
    }

    public ServerFilter() {
        this(Collections.emptySet(), true);
    }

    private final Collection<String> names;
    private final boolean whitelist;

    public boolean isAllowed(String name) {
        return names.contains(name) == whitelist;
    }
}
