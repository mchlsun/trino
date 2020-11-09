/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.password.ldap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.airlift.security.pem.PemReader;
import io.prestosql.spi.security.AccessDeniedException;

import javax.inject.Inject;
import javax.naming.AuthenticationException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.prestosql.plugin.password.jndi.JndiUtils.createDirContext;
import static java.util.Objects.requireNonNull;
import static javax.naming.Context.INITIAL_CONTEXT_FACTORY;
import static javax.naming.Context.PROVIDER_URL;
import static javax.naming.Context.REFERRAL;
import static javax.naming.Context.SECURITY_AUTHENTICATION;
import static javax.naming.Context.SECURITY_CREDENTIALS;
import static javax.naming.Context.SECURITY_PRINCIPAL;

public class JdkLdapAuthenticatorClient
        implements LdapAuthenticatorClient
{
    private static final Logger log = Logger.get(JdkLdapAuthenticatorClient.class);

    private final Map<String, String> basicEnvironment;
    private final Optional<SSLContext> sslContext;

    @Inject
    public JdkLdapAuthenticatorClient(LdapConfig ldapConfig)
    {
        String ldapUrl = requireNonNull(ldapConfig.getLdapUrl(), "ldapUrl is null");
        if (ldapUrl.startsWith("ldap://")) {
            log.warn("Passwords will be sent in the clear to the LDAP server. Please consider using SSL to connect.");
        }

        this.basicEnvironment = ImmutableMap.<String, String>builder()
                .put(INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
                .put(PROVIDER_URL, ldapUrl)
                .put(REFERRAL, ldapConfig.isIgnoreReferrals() ? "ignore" : "follow")
                .build();

        this.sslContext = Optional.ofNullable(ldapConfig.getTrustCertificate())
                .map(JdkLdapAuthenticatorClient::createSslContext);
    }

    @Override
    public void validatePassword(String userDistinguishedName, String password)
            throws NamingException
    {
        createUserDirContext(userDistinguishedName, password).close();
    }

    @Override
    public boolean isGroupMember(String searchBase, String groupSearch, String contextUserDistinguishedName, String contextPassword)
            throws NamingException
    {
        DirContext context = createUserDirContext(contextUserDistinguishedName, contextPassword);
        try {
            NamingEnumeration<SearchResult> search = searchContext(searchBase, groupSearch, context);
            try {
                return search.hasMore();
            }
            finally {
                search.close();
            }
        }
        finally {
            context.close();
        }
    }

    @Override
    public Set<String> lookupUserDistinguishedNames(String searchBase, String searchFilter, String contextUserDistinguishedName, String contextPassword)
            throws NamingException
    {
        DirContext context = createUserDirContext(contextUserDistinguishedName, contextPassword);
        try {
            ImmutableSet.Builder<String> distinguishedNames = ImmutableSet.builder();
            NamingEnumeration<SearchResult> search = searchContext(searchBase, searchFilter, context);
            try {
                while (search.hasMore()) {
                    distinguishedNames.add(search.next().getNameInNamespace());
                }
                return distinguishedNames.build();
            }
            finally {
                search.close();
            }
        }
        finally {
            context.close();
        }
    }

    private static NamingEnumeration<SearchResult> searchContext(String searchBase, String searchFilter, DirContext context)
            throws NamingException
    {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        return context.search(searchBase, searchFilter, searchControls);
    }

    private DirContext createUserDirContext(String userDistinguishedName, String password)
            throws NamingException
    {
        Map<String, String> environment = createEnvironment(userDistinguishedName, password);
        try {
            // This is the actual Authentication piece. Will throw javax.naming.AuthenticationException
            // if the users password is not correct. Other exceptions may include IO (server not found) etc.
            DirContext context = createDirContext(environment);
            log.debug("Password validation successful for user DN [%s]", userDistinguishedName);
            return context;
        }
        catch (AuthenticationException e) {
            log.debug("Password validation failed for user DN [%s]: %s", userDistinguishedName, e.getMessage());
            throw new AccessDeniedException("Invalid credentials");
        }
    }

    private Map<String, String> createEnvironment(String userDistinguishedName, String password)
    {
        ImmutableMap.Builder<String, String> environment = ImmutableMap.<String, String>builder()
                .putAll(basicEnvironment)
                .put(SECURITY_AUTHENTICATION, "simple")
                .put(SECURITY_PRINCIPAL, userDistinguishedName)
                .put(SECURITY_CREDENTIALS, password);

        sslContext.ifPresent(context -> {
            LdapSslSocketFactory.setSslContextForCurrentThread(context);

            // see https://docs.oracle.com/javase/jndi/tutorial/ldap/security/ssl.html
            environment.put("java.naming.ldap.factory.socket", LdapSslSocketFactory.class.getName());
        });

        return environment.build();
    }

    private static SSLContext createSslContext(File trustCertificate)
    {
        try {
            KeyStore trustStore = PemReader.loadTrustStore(trustCertificate);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new RuntimeException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
            }

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustManagers, null);
            return sslContext;
        }
        catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
