package org.jenkinsci.plugins.osfbuildersuiteforsfcc.dataimport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import hudson.AbortException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.*;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.jenkinsci.plugins.osfbuildersuiteforsfcc.credentials.HTTPProxyCredentials;
import org.jenkinsci.plugins.osfbuildersuiteforsfcc.credentials.OpenCommerceAPICredentials;
import org.jenkinsci.plugins.osfbuildersuiteforsfcc.credentials.TwoFactorAuthCredentials;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.stream.Stream;

class OpenCommerceAPI {
    private String hostname;
    private HTTPProxyCredentials httpProxyCredentials;
    private Boolean disableSSLValidation;
    private TwoFactorAuthCredentials tfCredentials;
    private OpenCommerceAPICredentials ocCredentials;
    private String ocVersion;

    private String cacheAuthType;
    private String cacheAuthToken;
    private Long cacheAuthExpire;

    OpenCommerceAPI(
            String hostname,
            HTTPProxyCredentials httpProxyCredentials,
            Boolean disableSSLValidation,
            TwoFactorAuthCredentials tfCredentials,
            OpenCommerceAPICredentials ocCredentials,
            String ocVersion) throws IOException {

        this.hostname = hostname;
        this.httpProxyCredentials = httpProxyCredentials;
        this.disableSSLValidation = disableSSLValidation;
        this.tfCredentials = tfCredentials;
        this.ocCredentials = ocCredentials;
        this.ocVersion = ocVersion;

        this.cacheAuthType = "";
        this.cacheAuthToken = "";
        this.cacheAuthExpire = 0L;
    }

    private CloseableHttpClient getCloseableHttpClient() throws AbortException {
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        httpClientBuilder.setUserAgent("Jenkins (OSF Builder Suite For Salesforce Commerce Cloud)");
        httpClientBuilder.setDefaultCookieStore(new BasicCookieStore());

        httpClientBuilder.addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
            if (!request.containsHeader("Accept-Encoding")) {
                request.addHeader("Accept-Encoding", "gzip");
            }
        });

        httpClientBuilder.addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                Header header = entity.getContentEncoding();
                if (header != null) {
                    for (HeaderElement headerElement : header.getElements()) {
                        if (headerElement.getName().equalsIgnoreCase("gzip")) {
                            response.setEntity(new GzipDecompressingEntity(response.getEntity()));
                            return;
                        }
                    }
                }
            }
        });

        httpClientBuilder.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setBufferSize(5242880 /* 5 MegaBytes */)
                .setFragmentSizeHint(5242880 /* 5 MegaBytes */)
                .build()
        );

        httpClientBuilder.setDefaultRequestConfig(RequestConfig.custom()
                .setSocketTimeout(300000 /* 5 minutes */)
                .setConnectTimeout(300000 /* 5 minutes */)
                .setConnectionRequestTimeout(300000 /* 5 minutes */)
                .build()
        );

        // Proxy Auth
        if (httpProxyCredentials != null) {
            String httpProxyHost = httpProxyCredentials.getHost();
            String httpProxyPort = httpProxyCredentials.getPort();
            String httpProxyUsername = httpProxyCredentials.getUsername();
            String httpProxyPassword = httpProxyCredentials.getPassword().getPlainText();

            int httpProxyPortInteger;

            try {
                httpProxyPortInteger = Integer.parseInt(httpProxyPort);
            } catch (NumberFormatException e) {
                throw new AbortException(
                        String.format("Invalid value \"%s\" for HTTP proxy port!", httpProxyPort) + " " +
                                "Please enter a valid port number."
                );
            }

            if (httpProxyPortInteger <= 0 || httpProxyPortInteger > 65535) {
                throw new AbortException(
                        String.format("Invalid value \"%s\" for HTTP proxy port!", httpProxyPort) + " " +
                                "Please enter a valid port number."
                );
            }

            HttpHost httpClientProxy = new HttpHost(httpProxyHost, httpProxyPortInteger);
            httpClientBuilder.setProxy(httpClientProxy);

            CredentialsProvider httpCredentialsProvider = new BasicCredentialsProvider();

            if (StringUtils.isNotEmpty(httpProxyUsername) && StringUtils.isNotEmpty(httpProxyPassword)) {
                if (httpProxyUsername.contains("\\")) {
                    String domain = httpProxyUsername.substring(0, httpProxyUsername.indexOf("\\"));
                    String user = httpProxyUsername.substring(httpProxyUsername.indexOf("\\") + 1);

                    httpCredentialsProvider.setCredentials(
                            new AuthScope(httpProxyHost, httpProxyPortInteger),
                            new NTCredentials(user, httpProxyPassword, "", domain)
                    );
                } else {
                    httpCredentialsProvider.setCredentials(
                            new AuthScope(httpProxyHost, httpProxyPortInteger),
                            new UsernamePasswordCredentials(httpProxyUsername, httpProxyPassword)
                    );
                }
            }

            httpClientBuilder.setDefaultCredentialsProvider(httpCredentialsProvider);
        }

        return httpClientBuilder.build();
    }

    private CloseableHttpClient getCloseableHttpClientWithTwoFactorAuth() throws AbortException {
        HttpClientBuilder httpClientBuilder = HttpClients.custom();
        httpClientBuilder.setUserAgent("Jenkins (OSF Builder Suite For Salesforce Commerce Cloud)");
        httpClientBuilder.setDefaultCookieStore(new BasicCookieStore());

        httpClientBuilder.addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
            if (!request.containsHeader("Accept-Encoding")) {
                request.addHeader("Accept-Encoding", "gzip");
            }
        });

        httpClientBuilder.addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                Header header = entity.getContentEncoding();
                if (header != null) {
                    for (HeaderElement headerElement : header.getElements()) {
                        if (headerElement.getName().equalsIgnoreCase("gzip")) {
                            response.setEntity(new GzipDecompressingEntity(response.getEntity()));
                            return;
                        }
                    }
                }
            }
        });

        httpClientBuilder.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setBufferSize(5242880 /* 5 MegaBytes */)
                .setFragmentSizeHint(5242880 /* 5 MegaBytes */)
                .build()
        );

        httpClientBuilder.setDefaultRequestConfig(RequestConfig.custom()
                .setSocketTimeout(300000 /* 5 minutes */)
                .setConnectTimeout(300000 /* 5 minutes */)
                .setConnectionRequestTimeout(300000 /* 5 minutes */)
                .build()
        );

        // Proxy Auth
        if (httpProxyCredentials != null) {
            String httpProxyHost = httpProxyCredentials.getHost();
            String httpProxyPort = httpProxyCredentials.getPort();
            String httpProxyUsername = httpProxyCredentials.getUsername();
            String httpProxyPassword = httpProxyCredentials.getPassword().getPlainText();

            int httpProxyPortInteger;

            try {
                httpProxyPortInteger = Integer.parseInt(httpProxyPort);
            } catch (NumberFormatException e) {
                throw new AbortException(
                        String.format("Invalid value \"%s\" for HTTP proxy port!", httpProxyPort) + " " +
                                "Please enter a valid port number."
                );
            }

            if (httpProxyPortInteger <= 0 || httpProxyPortInteger > 65535) {
                throw new AbortException(
                        String.format("Invalid value \"%s\" for HTTP proxy port!", httpProxyPort) + " " +
                                "Please enter a valid port number."
                );
            }

            HttpHost httpClientProxy = new HttpHost(httpProxyHost, httpProxyPortInteger);
            httpClientBuilder.setProxy(httpClientProxy);

            CredentialsProvider httpCredentialsProvider = new BasicCredentialsProvider();

            if (StringUtils.isNotEmpty(httpProxyUsername) && StringUtils.isNotEmpty(httpProxyPassword)) {
                if (httpProxyUsername.contains("\\")) {
                    String domain = httpProxyUsername.substring(0, httpProxyUsername.indexOf("\\"));
                    String user = httpProxyUsername.substring(httpProxyUsername.indexOf("\\") + 1);

                    httpCredentialsProvider.setCredentials(
                            new AuthScope(httpProxyHost, httpProxyPortInteger),
                            new NTCredentials(user, httpProxyPassword, "", domain)
                    );
                } else {
                    httpCredentialsProvider.setCredentials(
                            new AuthScope(httpProxyHost, httpProxyPortInteger),
                            new UsernamePasswordCredentials(httpProxyUsername, httpProxyPassword)
                    );
                }
            }

            httpClientBuilder.setDefaultCredentialsProvider(httpCredentialsProvider);
        }

        SSLContextBuilder sslContextBuilder = SSLContexts.custom();

        if (tfCredentials != null) {
            Provider bouncyCastleProvider = new BouncyCastleProvider();

            // Server Certificate
            Reader serverCertificateReader = new StringReader(tfCredentials.getServerCertificate());
            PEMParser serverCertificateParser = new PEMParser(serverCertificateReader);

            JcaX509CertificateConverter serverCertificateConverter = new JcaX509CertificateConverter();
            serverCertificateConverter.setProvider(bouncyCastleProvider);

            X509Certificate serverCertificate;

            try {
                serverCertificate = serverCertificateConverter.getCertificate(
                        (X509CertificateHolder) serverCertificateParser.readObject()
                );
            } catch (CertificateException | IOException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while loading two factor auth server certificate!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                serverCertificate.checkValidity();
            } catch (CertificateExpiredException e) {
                AbortException abortException = new AbortException(String.format(
                        "The server certificate used for two factor auth is expired!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            } catch (CertificateNotYetValidException e) {
                AbortException abortException = new AbortException(String.format(
                        "The server certificate used for two factor auth is not yet valid!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            // Client Certificate
            Reader clientCertificateReader = new StringReader(tfCredentials.getClientCertificate());
            PEMParser clientCertificateParser = new PEMParser(clientCertificateReader);

            JcaX509CertificateConverter clientCertificateConverter = new JcaX509CertificateConverter();
            clientCertificateConverter.setProvider(bouncyCastleProvider);

            X509Certificate clientCertificate;

            try {
                clientCertificate = clientCertificateConverter.getCertificate(
                        (X509CertificateHolder) clientCertificateParser.readObject()
                );
            } catch (CertificateException | IOException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while loading two factor auth client certificate!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                clientCertificate.checkValidity();
            } catch (CertificateExpiredException e) {
                AbortException abortException = new AbortException(String.format(
                        "The client certificate used for two factor auth is expired!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            } catch (CertificateNotYetValidException e) {
                AbortException abortException = new AbortException(String.format(
                        "The client certificate used for two factor auth is not yet valid!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            // Client Private Key
            Reader clientPrivateKeyReader = new StringReader(tfCredentials.getClientPrivateKey());
            PEMParser clientPrivateKeyParser = new PEMParser(clientPrivateKeyReader);

            Object clientPrivateKeyObject;

            try {
                clientPrivateKeyObject = clientPrivateKeyParser.readObject();
            } catch (IOException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while loading two factor auth client private key!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            PrivateKeyInfo clientPrivateKeyInfo;

            if (clientPrivateKeyObject instanceof PrivateKeyInfo) {
                clientPrivateKeyInfo = (PrivateKeyInfo) clientPrivateKeyObject;
            } else if (clientPrivateKeyObject instanceof PEMKeyPair) {
                clientPrivateKeyInfo = ((PEMKeyPair) clientPrivateKeyObject).getPrivateKeyInfo();
            } else {
                throw new AbortException("Failed to load two factor auth client private key!");
            }

            // Trust Store
            KeyStore customTrustStore;

            try {
                customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            } catch (KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom trust store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                customTrustStore.load(null, null);
            } catch (IOException | NoSuchAlgorithmException | CertificateException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom trust store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                customTrustStore.setCertificateEntry(hostname, serverCertificate);
            } catch (KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom trust store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                sslContextBuilder.loadTrustMaterial(customTrustStore, null);
            } catch (NoSuchAlgorithmException | KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom trust store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            // Key Store
            KeyFactory customKeyStoreKeyFactory;

            try {
                customKeyStoreKeyFactory = KeyFactory.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            PrivateKey customKeyStorePrivateKey;

            try {
                customKeyStorePrivateKey = customKeyStoreKeyFactory.generatePrivate(
                        new PKCS8EncodedKeySpec(clientPrivateKeyInfo.getEncoded())
                );
            } catch (InvalidKeySpecException | IOException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            KeyStore customKeyStore;

            try {
                customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            } catch (KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                customKeyStore.load(null, null);
            } catch (IOException | NoSuchAlgorithmException | CertificateException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            char[] keyStorePassword = RandomStringUtils.randomAscii(32).toCharArray();

            try {
                customKeyStore.setKeyEntry(
                        hostname, customKeyStorePrivateKey, keyStorePassword,
                        new X509Certificate[]{clientCertificate, serverCertificate}
                );
            } catch (KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }

            try {
                sslContextBuilder.loadKeyMaterial(customKeyStore, keyStorePassword);
            } catch (NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }
        }

        if (disableSSLValidation != null && disableSSLValidation) {
            try {
                sslContextBuilder.loadTrustMaterial(null, (TrustStrategy) (arg0, arg1) -> true);
            } catch (NoSuchAlgorithmException | KeyStoreException e) {
                AbortException abortException = new AbortException(String.format(
                        "Exception thrown while setting up the custom key store!\n%s",
                        ExceptionUtils.getStackTrace(e)
                ));
                abortException.initCause(e);
                throw abortException;
            }
        }

        SSLContext customSSLContext;

        try {
            customSSLContext = sslContextBuilder.build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while creating custom SSL context!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        if (disableSSLValidation != null && disableSSLValidation) {
            httpClientBuilder.setSSLSocketFactory(
                    new SSLConnectionSocketFactory(
                            customSSLContext, NoopHostnameVerifier.INSTANCE
                    )
            );
        } else {
            httpClientBuilder.setSSLSocketFactory(
                    new SSLConnectionSocketFactory(
                            customSSLContext, SSLConnectionSocketFactory.getDefaultHostnameVerifier()
                    )
            );
        }

        return httpClientBuilder.build();
    }

    private AuthResponse auth() throws IOException {
        Long currentTs = new Date().getTime() / 1000L;
        if (cacheAuthExpire > currentTs) {
            return new AuthResponse(cacheAuthToken, cacheAuthType);
        }

        List<NameValuePair> httpPostParams = new ArrayList<>();
        httpPostParams.add(new BasicNameValuePair("grant_type", "client_credentials"));

        RequestBuilder requestBuilder = RequestBuilder.create("POST");
        requestBuilder.setHeader("Authorization", String.format(
                "Basic %s",
                Base64.getEncoder().encodeToString(
                        String.format(
                                "%s:%s",
                                URLEncoder.encode(ocCredentials.getClientId(), "UTF-8"),
                                URLEncoder.encode(ocCredentials.getClientPassword().getPlainText(), "UTF-8")
                        ).getBytes(StandardCharsets.UTF_8)
                )
        ));

        requestBuilder.setUri("https://account.demandware.com/dwsso/oauth2/access_token");
        requestBuilder.setEntity(new UrlEncodedFormEntity(httpPostParams, Consts.UTF_8));

        CloseableHttpClient httpClient = getCloseableHttpClient();
        CloseableHttpResponse httpResponse;

        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        String httpEntityString;

        try {
            httpEntityString = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpResponse.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpClient.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while closing HTTP client!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        StatusLine httpStatusLine = httpResponse.getStatusLine();

        if (httpStatusLine.getStatusCode() != HttpStatus.SC_OK) {
            throw new AbortException(String.format(
                    "Failed to authenticate with OCAPI! %s - %s!\nResponse=%s",
                    httpStatusLine.getStatusCode(),
                    httpStatusLine.getReasonPhrase(),
                    httpEntityString
            ));
        }

        JsonElement jsonElement;

        try {
            JsonParser jsonParser = new JsonParser();
            jsonElement = jsonParser.parse(httpEntityString);
        } catch (JsonParseException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while parsing OCAPI JSON response!\nResponse=%s\n%s",
                    httpEntityString,
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        if (!jsonElement.isJsonObject()) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        boolean isValidJson = Stream.of("access_token", "token_type", "expires_in").allMatch(jsonObject::has);

        if (!isValidJson) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        String accessToken = jsonObject.get("access_token").getAsString();
        String tokenType = jsonObject.get("token_type").getAsString();
        long expiresIn = jsonObject.get("expires_in").getAsLong();

        cacheAuthToken = accessToken;
        cacheAuthType = tokenType;
        cacheAuthExpire = (new Date().getTime() / 1000L) + expiresIn - 60;

        return new AuthResponse(cacheAuthToken, cacheAuthType);
    }

    void cleanupLeftoverData(String path) throws IOException {
        AuthResponse authResponse = auth();

        RequestBuilder requestBuilder = RequestBuilder.create("DELETE");
        requestBuilder.setHeader("Authorization", String.format(
                "%s %s",
                authResponse.getAuthType(),
                authResponse.getAuthToken()
        ));

        requestBuilder.setUri(String.format(
                "https://%s/on/demandware.servlet/webdav/Sites/Impex/src/instance/%s",
                hostname,
                URLEncoder.encode(path, "UTF-8")
        ));

        CloseableHttpClient httpClient = getCloseableHttpClientWithTwoFactorAuth();
        CloseableHttpResponse httpResponse;

        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpResponse.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpClient.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while closing HTTP client!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        StatusLine httpStatusLine = httpResponse.getStatusLine();

        // Sometimes SFCC can't delete the whole folder at once and it will delete it partially and return
        // 207 http code and try to finish deleting the rest of the files in the background
        if (httpStatusLine.getStatusCode() == HttpStatus.SC_MULTI_STATUS) {
            return;
        }

        if (!Arrays.asList(HttpStatus.SC_NOT_FOUND, HttpStatus.SC_NO_CONTENT).contains(httpStatusLine.getStatusCode())) {
            throw new AbortException(String.format(
                    "%s - %s!", httpStatusLine.getStatusCode(), httpStatusLine.getReasonPhrase()
            ));
        }
    }

    void uploadData(File dataZip, String archiveName) throws IOException {
        AuthResponse authResponse = auth();

        RequestBuilder requestBuilder = RequestBuilder.create("PUT");
        requestBuilder.setHeader("Authorization", String.format(
                "%s %s",
                authResponse.getAuthType(),
                authResponse.getAuthToken()
        ));

        requestBuilder.setEntity(new FileEntity(dataZip, ContentType.APPLICATION_OCTET_STREAM));
        requestBuilder.setUri(String.format(
                "https://%s/on/demandware.servlet/webdav/Sites/Impex/src/instance/%s.zip",
                hostname,
                URLEncoder.encode(archiveName, "UTF-8")
        ));

        CloseableHttpClient httpClient = getCloseableHttpClientWithTwoFactorAuth();
        CloseableHttpResponse httpResponse;

        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpResponse.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpClient.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while closing HTTP client!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        StatusLine httpStatusLine = httpResponse.getStatusLine();

        if (httpStatusLine.getStatusCode() != HttpStatus.SC_CREATED) {
            throw new AbortException(String.format(
                    "%s - %s!", httpStatusLine.getStatusCode(), httpStatusLine.getReasonPhrase()
            ));
        }
    }

    JobExecutionResult executeSiteArchiveImportJob(String archiveName) throws IOException {
        AuthResponse authResponse = auth();

        RequestBuilder requestBuilder = RequestBuilder.create("POST");
        requestBuilder.setHeader("Authorization", String.format(
                "%s %s",
                authResponse.getAuthType(),
                authResponse.getAuthToken()
        ));

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("file_name", String.format("%s.zip", archiveName));
        requestJson.addProperty("mode", "merge");
        requestBuilder.setEntity(new StringEntity(requestJson.toString(), ContentType.APPLICATION_JSON));

        requestBuilder.setUri(String.format(
                "https://%s/s/-/dw/data/%s/jobs/sfcc-site-archive-import/executions?client_id=%s",
                hostname,
                URLEncoder.encode(ocVersion, "UTF-8"),
                URLEncoder.encode(ocCredentials.getClientId(), "UTF-8")
        ));

        CloseableHttpClient httpClient = getCloseableHttpClientWithTwoFactorAuth();
        CloseableHttpResponse httpResponse;

        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        String httpEntityString;

        try {
            httpEntityString = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpResponse.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpClient.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while closing HTTP client!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        JsonElement jsonElement;

        try {
            JsonParser jsonParser = new JsonParser();
            jsonElement = jsonParser.parse(httpEntityString);
        } catch (JsonParseException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while parsing OCAPI JSON response!\nResponse=%s\n%s",
                    httpEntityString,
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        StatusLine httpStatusLine = httpResponse.getStatusLine();

        if (!Arrays.asList(HttpStatus.SC_OK, HttpStatus.SC_ACCEPTED).contains(httpStatusLine.getStatusCode())) {
            throw new AbortException(String.format(
                    "Failed to execute OCAPI data import job! %s - %s!\nResponse=%s",
                    httpStatusLine.getStatusCode(),
                    httpStatusLine.getReasonPhrase(),
                    httpEntityString
            ));
        }

        if (!jsonElement.isJsonObject()) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI execute data import job JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        boolean isValidJson = Stream.of("execution_status", "id").allMatch(jsonObject::has);

        if (!isValidJson) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI execute data import job JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        String jobId = jsonObject.get("id").getAsString();
        String jobStatus = jsonObject.get("execution_status").getAsString();
        return new JobExecutionResult(jobId, jobStatus);
    }

    JobExecutionResult checkSiteArchiveImportJob(String archiveName, String jobId) throws IOException {
        AuthResponse authResponse = auth();

        RequestBuilder requestBuilder = RequestBuilder.create("GET");
        requestBuilder.setHeader("Authorization", String.format(
                "%s %s",
                authResponse.getAuthType(),
                authResponse.getAuthToken()
        ));

        requestBuilder.setUri(String.format(
                "https://%s/s/-/dw/data/%s/jobs/sfcc-site-archive-import/executions/%s?client_id=%s",
                hostname,
                URLEncoder.encode(ocVersion, "UTF-8"),
                URLEncoder.encode(jobId, "UTF-8"),
                URLEncoder.encode(ocCredentials.getClientId(), "UTF-8")
        ));

        CloseableHttpClient httpClient = getCloseableHttpClientWithTwoFactorAuth();
        CloseableHttpResponse httpResponse;

        try {
            httpResponse = httpClient.execute(requestBuilder.build());
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        String httpEntityString;

        try {
            httpEntityString = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpResponse.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while making HTTP request!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        try {
            httpClient.close();
        } catch (IOException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while closing HTTP client!\n%s",
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        JsonElement jsonElement;

        try {
            JsonParser jsonParser = new JsonParser();
            jsonElement = jsonParser.parse(httpEntityString);
        } catch (JsonParseException e) {
            AbortException abortException = new AbortException(String.format(
                    "Exception thrown while parsing OCAPI JSON response!\nResponse=%s\n%s",
                    httpEntityString,
                    ExceptionUtils.getStackTrace(e)
            ));
            abortException.initCause(e);
            throw abortException;
        }

        StatusLine httpStatusLine = httpResponse.getStatusLine();

        if (httpStatusLine.getStatusCode() != HttpStatus.SC_OK) {
            throw new AbortException(String.format(
                    "Failed to get OCAPI data import job status! %s - %s!\nResponse=%s",
                    httpStatusLine.getStatusCode(),
                    httpStatusLine.getReasonPhrase(),
                    httpEntityString
            ));
        }

        if (!jsonElement.isJsonObject()) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI get data import job JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if (!jsonObject.has("execution_status")) {
            throw new AbortException(String.format(
                    "Failed to parse OCAPI get data import job JSON response!\nResponse=%s",
                    httpEntityString
            ));
        }

        JsonElement jsonExecutionStatus = jsonObject.get("execution_status");
        String executionStatus = jsonExecutionStatus.getAsString();

        if (StringUtils.equalsIgnoreCase(executionStatus, "finished")) {
            if (!jsonObject.has("exit_status")) {
                throw new AbortException(String.format(
                        "Failed to parse OCAPI get data import job JSON response!\nResponse=%s",
                        httpEntityString
                ));
            }

            JsonElement exitStatusElement = jsonObject.get("exit_status");

            if (!exitStatusElement.isJsonObject()) {
                throw new AbortException(String.format(
                        "Failed to parse OCAPI get data import job JSON response!\nResponse=%s",
                        httpEntityString
                ));
            }

            JsonObject exitStatusObject = exitStatusElement.getAsJsonObject();

            JsonElement exitStatusStatusElement = exitStatusObject.get("status");
            String exitStatusStatus = exitStatusStatusElement.getAsString();

            if (!StringUtils.equalsIgnoreCase(exitStatusStatus, "ok")) {
                throw new AbortException(String.format(
                        "Failed to import %s.zip!\nResponse=%s",
                        archiveName,
                        httpEntityString
                ));
            }
        }

        String jobStatus = jsonObject.get("execution_status").getAsString();
        return new JobExecutionResult(jobId, jobStatus);
    }

    private static final class AuthResponse {
        private String authToken;
        private String authType;

        AuthResponse(String authToken, String authType) {
            this.authToken = authToken;
            this.authType = authType;
        }

        String getAuthToken() {
            return authToken;
        }

        String getAuthType() {
            return authType;
        }
    }

    static final class JobExecutionResult {
        private String id;
        private String status;

        JobExecutionResult(String id, String status) {
            this.id = id;
            this.status = status;
        }

        String getId() {
            return id;
        }

        String getStatus() {
            return status;
        }
    }
}
