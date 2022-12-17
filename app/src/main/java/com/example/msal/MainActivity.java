package com.example.msal;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.SilentAuthenticationCallback;
import com.microsoft.identity.client.exception.MsalClientException;
import com.microsoft.identity.client.exception.MsalException;
import com.microsoft.identity.client.exception.MsalServiceException;
import com.microsoft.identity.client.exception.MsalUiRequiredException;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = AppCompatActivity.class.getSimpleName();

    Button signInButton;
    Button signOutButton;
    TextView logTextView;
    TextView currentUserTextView;
    WebView myWebView;


    /* Azure AD Variables */
    private ISingleAccountPublicClientApplication mSingleAccountApp;
    private IAccount mAccount;
    private String[] scopes = {"User.Read", "User.Read.All", "openid", "profile"};
    final private String defaultGraphResourceUrl = MSGraphRequestWrapper.MS_GRAPH_ROOT_ENDPOINT + "v1.0/me";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        PublicClientApplication.showExpectedMsalRedirectUriInfo(this);
        initializeUI();

        PublicClientApplication.createSingleAccountPublicClientApplication(getApplicationContext(),
                R.raw.auth_config_single_account, new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    @Override
                    public void onCreated(ISingleAccountPublicClientApplication application) {
                        mSingleAccountApp = application;
                        loadAccount();
                    }
                    @Override
                    public void onError(MsalException exception) {
                        displayError(exception);
                    }
                });

    }

    private void initializeUI() {
        signInButton = (Button) findViewById(R.id.btn_signIn);
        signOutButton = (Button) findViewById(R.id.btn_removeAccount);
        logTextView = (TextView) findViewById(R.id.txt_log);
        currentUserTextView = (TextView) findViewById(R.id.current_user);
        myWebView = (WebView) findViewById(R.id.webview);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(myWebView,true);
            CookieManager.getInstance().acceptThirdPartyCookies(myWebView);
        }


        signInButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mSingleAccountApp == null) {
                    return;
                }

                CookieManager.getInstance().setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(myWebView,true);
                    CookieManager.getInstance().acceptThirdPartyCookies(myWebView);
                }
                mSingleAccountApp.signIn(MainActivity.this, null, scopes, getAuthInteractiveCallback());
            }
        });

        signOutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mSingleAccountApp == null) {
                    return;
                }

                /**
                 * Removes the signed-in account and cached tokens from this app (or device, if the device is in shared mode).
                 */

                // clear webview
                WebStorage.getInstance().deleteAllData();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().removeSessionCookies(null);
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                }
                myWebView.clearCache(true);
                myWebView.clearFormData();
                myWebView.clearHistory();
                myWebView.clearSslPreferences();
                myWebView.loadUrl("about:blank");

                mSingleAccountApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
                    @Override
                    public void onSignOut() {
                        mAccount = null;
                        updateUI();
                        showToastOnSignOut();
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        displayError(exception);
                    }
                });
            }
        });
    }

    /**
     * Load the currently signed-in account, if there's any.
     */
    private void loadAccount() {
        if (mSingleAccountApp == null) {
            return;
        }

        mSingleAccountApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            @Override
            public void onAccountLoaded(@Nullable IAccount activeAccount) {
                // You can use the account data to update your UI or your app database.
                mAccount = activeAccount;
                updateUI();
            }

            @Override
            public void onAccountChanged(@Nullable IAccount priorAccount, @Nullable IAccount currentAccount) {
                if (currentAccount == null) {
                    // Perform a cleanup task as the signed-in account changed.
                    showToastOnSignOut();
                }
            }

            @Override
            public void onError(@NonNull MsalException exception) {
                displayError(exception);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        /**
         * The account may have been removed from the device (if broker is in use).
         *
         * In shared device mode, the account might be signed in/out by other apps while this app is not in focus.
         * Therefore, we want to update the account state by invoking loadAccount() here.
         */
        loadAccount();
    }

    /**
     * Callback used in for silent acquireToken calls.
     */
    private SilentAuthenticationCallback getAuthSilentCallback() {
        return new SilentAuthenticationCallback() {

            @Override
            public void onSuccess(IAuthenticationResult authenticationResult) {
                Log.d(TAG, "Successfully authenticated");

                /* Successfully got a token, use it to call a protected resource - MSGraph */
                callGraphAPI(authenticationResult);
            }

            @Override
            public void onError(MsalException exception) {
                /* Failed to acquireToken */
                Log.d(TAG, "Authentication failed: " + exception.toString());
                displayError(exception);

                if (exception instanceof MsalClientException) {
                    /* Exception inside MSAL, more info inside MsalError.java */
                } else if (exception instanceof MsalServiceException) {
                    /* Exception when communicating with the STS, likely config issue */
                } else if (exception instanceof MsalUiRequiredException) {
                    /* Tokens expired or no session, retry with interactive */
                }
            }
        };
    }

    /**
     * Callback used for interactive request.
     * If succeeds we use the access token to call the Microsoft Graph.
     * Does not check cache.
     */
    private AuthenticationCallback getAuthInteractiveCallback() {
        return new AuthenticationCallback() {

            @Override
            public void onSuccess(IAuthenticationResult authenticationResult) {
                /* Successfully got a token, use it to call a protected resource - MSGraph */
                Log.d(TAG, "Successfully authenticated");
//                Log.d(TAG, "ID Token: " + authenticationResult.getAccount().getClaims().get("id_token"));
//                Log.d(TAG, "Access Token: " + authenticationResult.getAccount().getClaims().get("access_token"));
                Log.d(TAG, "Access Token: " + authenticationResult.getAccessToken());
                Log.d(TAG, "ID Token: " + authenticationResult.getAccount().getIdToken());

                /* Update account */
                mAccount = authenticationResult.getAccount();
                updateUI();

                /* call graph */
                callGraphAPI(authenticationResult);

            }

            @Override
            public void onError(MsalException exception) {
                /* Failed to acquireToken */
                Log.d(TAG, "Authentication failed: " + exception.toString());
                displayError(exception);

                if (exception instanceof MsalClientException) {
                    /* Exception inside MSAL, more info inside MsalError.java */
                } else if (exception instanceof MsalServiceException) {
                    /* Exception when communicating with the STS, likely config issue */
                }
            }

            @Override
            public void onCancel() {
                /* User canceled the authentication */
                Log.d(TAG, "User cancelled login.");
            }
        };
    }

    /**
     * Make an HTTP request to obtain MSGraph data
     */
    private void callGraphAPI(final IAuthenticationResult authenticationResult) {
        MSGraphRequestWrapper.callGraphAPIUsingVolley(
                getApplicationContext(),
                defaultGraphResourceUrl,
                authenticationResult.getAccessToken(),
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        /* Successfully called graph, process data and send to UI */
                        Log.d(TAG, "Response: " + response.toString());
                        displayGraphResult(response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d(TAG, "Error: " + error.toString());
                        displayError(error);
                    }
                });
    }

    /**
     * Display the error message
     */
    private void displayError(@NonNull final Exception exception) {
        logTextView.setText(exception.toString());
    }

    /**
     * Display the graph response
     */
    private void displayGraphResult(@NonNull final JSONObject graphResponse) {
        logTextView.setText(graphResponse.toString());
    }

    private void updateUI() {
        if (mAccount != null) {
            signInButton.setEnabled(false);
            signOutButton.setEnabled(true);
            currentUserTextView.setText(mAccount.getUsername());

            //Webview
            //CookieSyncManager.getInstance().startSync();
//            Toast.makeText(this, CookieManager.getInstance().hasCookies() ? "YES" : "NO", Toast.LENGTH_LONG).show();
            myWebView.loadUrl("https://web.microsoftstream.com/embed/video/30d528d8-f4fd-4a3e-89ea-5941072d2f1e");

            // Test via web browser not android native webview, also change the authorization_user_agent to "BROWSER"
//                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://web.microsoftstream.com/embed/video/30d528d8-f4fd-4a3e-89ea-5941072d2f1e"));
//                startActivity(browserIntent);

        } else {
            signInButton.setEnabled(true);
            signOutButton.setEnabled(false);
            currentUserTextView.setText("None");
        }
        String isSharedDevice = mSingleAccountApp.isSharedDevice() ? "Yes" : "No";
        Log.d(TAG, "Is Shared Device Enabled: " + isSharedDevice);
    }

    /**
     * Updates UI when app sign out succeeds
     */
    private void showToastOnSignOut() {
        final String signOutText = "Signed Out.";
        currentUserTextView.setText("");
        Toast.makeText(getApplicationContext(), signOutText, Toast.LENGTH_SHORT)
                .show();
    }
}