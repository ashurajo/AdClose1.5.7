package com.close.hook.ads.hook.gc.network;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.close.hook.ads.BlockedBean;
import com.close.hook.ads.IBlockedStatusProvider;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;

import org.luckypray.dexkit.result.MethodData;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";

    private static IBlockedStatusProvider mStub;
    private static boolean isBound = false;

    public static void init() {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

    private static void setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
                InetAddress.class,
                "getByName",
                "before",
                param -> {
                    String host = (String) param.args[0];
                    if (host != null && shouldBlockDnsRequest(host)) {
                        param.setResult(null);
                    }
                },
                String.class
        );

        HookUtil.findAndHookMethod(
                InetAddress.class,
                "getAllByName",
                "before",
                param -> {
                    String host = (String) param.args[0];
                    if (host != null && shouldBlockDnsRequest(host)) {
                        param.setResult(new InetAddress[0]);
                    }
                },
                String.class
        );
    }

    private static boolean shouldBlockDnsRequest(String host) {
        return checkShouldBlockRequest(host, null, " DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " OKHTTP", "url");
    }

    private static boolean checkShouldBlockRequest(final String queryValue, final RequestDetails details, final String requestType, final String queryType) {
        Triple<Boolean, String, String> triple = queryContentProvider(queryType, queryValue);
        boolean shouldBlock = triple.getFirst();
        String blockType = triple.getSecond();
        String url = triple.getThird();

        sendBroadcast(requestType, shouldBlock, blockType, url, queryValue, details);
        return shouldBlock;
    }

    private static String formatUrlWithoutQuery(URL url) {
        try {
            URL formattedUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath());
            return formattedUrl.toExternalForm();
        } catch (MalformedURLException e) {
            XposedBridge.log(LOG_PREFIX + "Malformed URL: " + e.getMessage());
            return null;
        }
    }

    public static Triple<Boolean, String, String> queryContentProvider(String queryType, String queryValue) {
        if (mStub == null) {
            Context context = ContextUtil.appContext;
            if (context != null) {
                bindService(context);
            }
        }

        try {
            if (mStub != null) {
                BlockedBean blockedBean = mStub.getData(queryType.replace("host", "Domain").replace("url", "URL"), queryValue);
                return new Triple<>(blockedBean.isBlocked(), blockedBean.getType(), blockedBean.getValue());
            }
        } catch (RemoteException e) {
            Log.e(LOG_PREFIX, "RemoteException during service communication", e);
        }

        return new Triple<>(false, null, null);
    }

    private static void bindService(Context context) {
        if (!isBound) {
            Intent intent = new Intent();
            intent.setClassName("com.close.hook.ads", "com.close.hook.ads.service.AidlService");
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private static final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mStub = IBlockedStatusProvider.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mStub = null;
            isBound = false;
        }
    };

    private static void setupHttpRequestHook() {
        try {
            HookUtil.hookAllMethods("com.android.okhttp.internal.huc.HttpURLConnectionImpl", "getResponse", "after", param -> {
                try {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");

                    boolean isResponseAvailable = (boolean) XposedHelpers.callMethod(httpEngine, "hasResponse");
                    if (!isResponseAvailable) {
                        return;
                    }

                    Object response = XposedHelpers.callMethod(httpEngine, "getResponse");
                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    Object httpUrl = XposedHelpers.callMethod(request, "urlString");
                    URL url = new URL(httpUrl.toString());

                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = processRequest(request, response, url, stackTrace);

                    if (details != null && shouldBlockHttpsRequest(url, details)) {
                        Object emptyResponse = createEmptyResponseForHttp(response);

                        Field userResponseField = httpEngine.getClass().getDeclaredField("userResponse");
                        userResponseField.setAccessible(true);
                        userResponseField.set(httpEngine, emptyResponse);
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Exception in HTTP connection hook: " + e.getMessage());
                }
            }, ContextUtil.appContext.getClassLoader());
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static Object createEmptyResponseForHttp(Object response) throws Exception {
        if (response.getClass().getName().equals("com.android.okhttp.Response")) {
            Class<?> responseClass = response.getClass();

            Class<?> builderClass = Class.forName("com.android.okhttp.Response$Builder");
            Class<?> requestClass = Class.forName("com.android.okhttp.Request");
            Class<?> protocolClass = Class.forName("com.android.okhttp.Protocol");
            Class<?> responseBodyClass = Class.forName("com.android.okhttp.ResponseBody");

            Object request = XposedHelpers.callMethod(response, "request");

            Object builder = builderClass.newInstance();

            builderClass.getMethod("request", requestClass).invoke(builder, request);

            Object protocolHTTP11 = protocolClass.getField("HTTP_1_1").get(null);
            builderClass.getMethod("protocol", protocolClass).invoke(builder, protocolHTTP11);

            builderClass.getMethod("code", int.class).invoke(builder, 204); // 204 No Content
            builderClass.getMethod("message", String.class).invoke(builder, "No Content");

            Method createMethod = responseBodyClass.getMethod("create", Class.forName("com.android.okhttp.MediaType"), String.class);
            Object emptyResponseBody = createMethod.invoke(null, null, "");

            builderClass.getMethod("body", responseBodyClass).invoke(builder, emptyResponseBody);

            return builderClass.getMethod("build").invoke(builder);
        }
        return null;
    }

    public static void setupOkHttpRequestHook() {
        hookMethod("setupOkHttpRequestHook", "Already Executed", "execute");  // okhttp3.Call.execute -overload method
        hookMethod("setupOkHttp2RequestHook", "Canceled", "intercept");  // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
    }

    private static void hookMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = ContextUtil.appContext.getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(ContextUtil.appContext.getClassLoader());
                    HookUtil.hookMethod(method, "after", param -> {
                        try {
                            Object response = param.getResult();
                            if (response == null) {
                                return;
                            }

                            Object request = XposedHelpers.callMethod(response, "request");
                            Object okhttpUrl = XposedHelpers.callMethod(request, "url");
                            URL url = new URL(okhttpUrl.toString());

                            String stackTrace = HookUtil.getFormattedStackTrace();
                            RequestDetails details = processRequest(request, response, url, stackTrace);

                            if (details != null && shouldBlockOkHttpsRequest(url, details)) {
                                throw new IOException("Request blocked");
                            }
                        } catch (IOException e) {
                            param.setThrowable(e);
                        } catch (Exception e) {
                            XposedBridge.log("Error processing method hook: " + methodData + ", " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log("Error hooking method: " + methodData + ", " + e.getMessage());
                }
            }
        }
    }

    private static RequestDetails processRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Exception in processing request: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(
            String requestType, boolean shouldBlock, String blockType, String ruleUrl,
            String url, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockType, url, details);
        if (shouldBlock) {
            sendBlockedRequestBroadcast("block", requestType, true, ruleUrl, blockType, url, details);
        } else {
            sendBlockedRequestBroadcast("pass", requestType, false, ruleUrl, blockType, url, details);
        }
    }

    private static void sendBlockedRequestBroadcast(
            String type, @Nullable String requestType, @Nullable Boolean isBlocked,
            @Nullable String url, @Nullable String blockType, String request,
            RequestDetails details) {
        Intent intent = new Intent("com.rikkati.REQUEST");

        Context currentContext = ContextUtil.appContext;
        if (currentContext != null) {
            AppInfo appInfo = new AppInfo("AppName", currentContext.getPackageName(), null, "1.0", 1, 0L, 0L, 0L, 0, 0, 1, 1);
            String appName = appInfo.getAppName() + requestType;
            String packageName = appInfo.getPackageName();

            BlockedRequest blockedRequest;

            String method = details != null ? details.getMethod() : null;
            String urlString = details != null ? details.getUrlString() : null;
            String requestHeaders = details != null && details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null;
            int responseCode = details != null ? details.getResponseCode() : -1;
            String responseMessage = details != null ? details.getResponseMessage() : null;
            String responseHeaders = details != null && details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null;
            String stackTrace = details != null ? details.getStack() : null;

            blockedRequest = new BlockedRequest(
                    appName,
                    packageName,
                    request,
                    System.currentTimeMillis(),
                    type,
                    isBlocked,
                    url,
                    blockType,
                    method,
                    urlString,
                    requestHeaders,
                    responseCode,
                    responseMessage,
                    responseHeaders,
                    stackTrace
            );

            intent.putExtra("request", blockedRequest);
            currentContext.sendBroadcast(intent);
        } else {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
        }
    }
}
