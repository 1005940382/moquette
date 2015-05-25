/*
 * Copyright (c) 2012-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package org.eclipse.moquette.spi.impl.security;

import org.eclipse.moquette.spi.impl.subscriptions.SubscriptionsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.*;

import static org.eclipse.moquette.spi.impl.security.Authorization.Permission.READ;
import static org.eclipse.moquette.spi.impl.security.Authorization.Permission.READWRITE;
import static org.eclipse.moquette.spi.impl.security.Authorization.Permission.WRITE;

/**
 * Used by the ACLFileParser to push all authorizations it finds.
 * ACLAuthorizator uses it in read mode to check it topics matches the ACLs.
 *
 * Not thread safe.
 *
 * @author andrea
 */
class AuthorizationsCollector implements IAuthorizator {

    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationsCollector.class);

    private List<Authorization> m_globalAuthorizations = new ArrayList();
    private List<Authorization> m_patternAuthorizations = new ArrayList();
    private Map<String, List<Authorization>> m_userAuthorizations = new HashMap();
    private boolean m_parsingUsersSpecificSection = false;
    private String m_currentUser = "";

    static final AuthorizationsCollector emptyImmutableCollector() {
        AuthorizationsCollector coll = new AuthorizationsCollector();
        coll.m_globalAuthorizations = Collections.emptyList();
        coll.m_patternAuthorizations = Collections.emptyList();
        coll.m_userAuthorizations = Collections.emptyMap();
        return coll;
    }

    void parse(String line) throws ParseException {
        Authorization acl = parseAuthLine(line);
        if (acl == null) {
            //skip it's a user
            return;
        }
        if (m_parsingUsersSpecificSection) {
            //TODO in java 8 switch to m_userAuthorizations.putIfAbsent(m_currentUser, new ArrayList());
            if (!m_userAuthorizations.containsKey(m_currentUser)) {
                m_userAuthorizations.put(m_currentUser, new ArrayList());
            }
            List<Authorization> userAuths = m_userAuthorizations.get(m_currentUser);
            userAuths.add(acl);
        } else {
            m_globalAuthorizations.add(acl);
        }
    }

    protected Authorization parseAuthLine(String line) throws ParseException {
        String[] tokens = line.split("\\s+");
        String keyword = tokens[0].toLowerCase();
        switch (keyword) {
            case "topic":
                if (tokens.length > 2) {
                    //if the tokenized lines has 3 token the second must be the permission
                    try {
                        Authorization.Permission permission = Authorization.Permission.valueOf(tokens[1].toUpperCase());
                        //bring topic with all original spacing
                        String topic = line.substring(line.indexOf(tokens[2]));

                        return new Authorization(topic, permission);
                    } catch (IllegalArgumentException iaex) {
                        throw new ParseException("invalid permission token", 1);
                    }
                }
                String topic = tokens[1];
                return new Authorization(topic);
            case "user":
                m_parsingUsersSpecificSection = true;
                m_currentUser = tokens[1];
                return null;
            case "pattern":
                //TODO implement the part for patter matching
                return null;
            default:
                throw new ParseException(String.format("invalid line definition found %s", line), 1);
        }
    }

    @Override
    public boolean canWrite(String topic, String user) {
        return canDoOperation(topic, WRITE, user);
    }

    @Override
    public boolean canRead(String topic, String user) {
        return canDoOperation(topic, READ, user);
    }

    private boolean canDoOperation(String topic, Authorization.Permission permission, String username) {
        if (username == null || username.isEmpty()) {
            for (Authorization auth : m_globalAuthorizations) {
                if (auth.permission == permission || auth.permission == READWRITE) {
                    if (SubscriptionsStore.matchTopics(topic, auth.topic)) {
                        return true;
                    }
                }
            }
        } else if (m_userAuthorizations.containsKey(username)) {
            for (Authorization auth : m_userAuthorizations.get(username)) {
                if (auth.permission == permission || auth.permission == READWRITE) {
                    if (SubscriptionsStore.matchTopics(topic, auth.topic)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return m_globalAuthorizations.isEmpty();
    }
}
