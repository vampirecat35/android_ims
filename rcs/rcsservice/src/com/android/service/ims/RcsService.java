/*
 * Copyright (c) 2015, Motorola Mobility LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of Motorola Mobility nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MOTOROLA MOBILITY LLC BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package com.android.service.ims;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature;

import com.android.ims.IRcsPresenceListener;
import com.android.ims.RcsManager.ResultCode;
import com.android.ims.internal.IRcsPresence;
import com.android.ims.internal.IRcsService;
import com.android.ims.internal.Logger;
import com.android.internal.telephony.IccCardConstants;
import com.android.service.ims.R;
import com.android.service.ims.presence.PresencePublication;
import com.android.service.ims.presence.PresenceSubscriber;

import java.util.List;

public class RcsService extends Service {

    private static final int IMS_SERVICE_RETRY_TIMEOUT_MS = 5000;

    private Logger logger = Logger.getLogger(this.getClass().getName());

    private RcsStackAdaptor mRcsStackAdaptor = null;
    private PresencePublication mPublication = null;
    private PresenceSubscriber mSubscriber = null;

    private Handler mRetryHandler;
    private Runnable mRegisterCallbacks = this::registerImsCallbacksAndSetAssociatedSubscription;
    private int mNetworkRegistrationType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;

    private int mAssociatedSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangedListener
            = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            registerImsCallbacksAndSetAssociatedSubscription();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        logger.debug("RcsService onCreate");

        mRcsStackAdaptor = RcsStackAdaptor.getInstance(this);

        mPublication = new PresencePublication(mRcsStackAdaptor, this,
                getResources().getStringArray(
                        R.array.config_volte_provision_error_on_publish_response),
                getResources().getStringArray(
                        R.array.config_rcs_provision_error_on_publish_response));
        mRcsStackAdaptor.getListener().setPresencePublication(mPublication);

        mSubscriber = new PresenceSubscriber(mRcsStackAdaptor, this,
                getResources().getStringArray(
                        R.array.config_volte_provision_error_on_subscribe_response),
                getResources().getStringArray(
                        R.array.config_rcs_provision_error_on_subscribe_response));
        mRcsStackAdaptor.getListener().setPresenceSubscriber(mSubscriber);
        mPublication.setSubscriber(mSubscriber);

        ConnectivityManager cm = ConnectivityManager.from(this);
        if (cm != null) {
            boolean enabled = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;
            logger.debug("Mobile data enabled status: " + (enabled ? "ON" : "OFF"));

            onMobileDataEnabled(enabled);
        }

        // TODO: support MSIM
        ServiceManager.addService("rcs", mBinder);

        mObserver = new MobileDataContentObserver();
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.MOBILE_DATA),
                false, mObserver);

        mSiminfoSettingObserver = new SimInfoContentObserver();
        getContentResolver().registerContentObserver(
                SubscriptionManager.CONTENT_URI, false, mSiminfoSettingObserver);

        mRetryHandler = new Handler(Looper.getMainLooper());
        registerSubscriptionChangedListener();
    }

    private void registerSubscriptionChangedListener() {
        SubscriptionManager subscriptionManager = getSystemService(SubscriptionManager.class);
        if (subscriptionManager != null) {
            // This will call back after the listener is added automatically.
            subscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        } else {
            logger.error("SubscriptionManager not available! Retrying...");
            // Retry this again after some time.
            mRetryHandler.postDelayed(this::registerSubscriptionChangedListener,
                    IMS_SERVICE_RETRY_TIMEOUT_MS);
        }
    }

    private void registerImsCallbacksAndSetAssociatedSubscription() {
        SubscriptionManager sm = getSystemService(SubscriptionManager.class);
        if (sm == null) {
            logger.warn("handleSubscriptionsChanged: SubscriptionManager is null!");
            return;
        }
        List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
        if (infos == null || infos.isEmpty()) {
            // There are no active subscriptions right now.
            handleImsServiceDown();
        } else {
            int defaultVoiceSub = SubscriptionManager.getDefaultVoiceSubscriptionId();
            // Get default voice id and then try to register for IMS callbacks
            if (defaultVoiceSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                for (SubscriptionInfo info : infos) {
                    if (!info.isOpportunistic()) {
                        defaultVoiceSub = info.getSubscriptionId();
                        break;
                    }
                }
            }
            if (defaultVoiceSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                handleImsServiceDown();
                return;
            }

            ImsMmTelManager mIms = ImsMmTelManager.createForSubscriptionId(defaultVoiceSub);
            try {
                if (defaultVoiceSub == mAssociatedSubscription) {
                    // Don't register duplicate callbacks for the same subscription.
                    return;
                }
                if (mAssociatedSubscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    // Get rid of any existing registrations.
                    ImsMmTelManager mOldIms = ImsMmTelManager.createForSubscriptionId(
                            mAssociatedSubscription);
                    mOldIms.unregisterImsRegistrationCallback(mImsRegistrationCallback);
                    mOldIms.unregisterMmTelCapabilityCallback(mCapabilityCallback);
                    logger.print("callbacks unregistered for sub " + mAssociatedSubscription);
                }
                // move over registrations.
                mIms.registerImsRegistrationCallback(getMainExecutor(), mImsRegistrationCallback);
                mIms.registerMmTelCapabilityCallback(getMainExecutor(), mCapabilityCallback);
                mAssociatedSubscription = defaultVoiceSub;
                logger.print("callbacks registered for sub " + mAssociatedSubscription);
                handleImsServiceUp();
            } catch (ImsException e) {
                logger.info("Couldn't register callbacks for " + defaultVoiceSub + ": "
                        + e.getMessage());
                if (e.getCode() == ImsException.CODE_ERROR_SERVICE_UNAVAILABLE) {
                    // IMS temporarily unavailable. Retry after a few seconds.
                    mRetryHandler.removeCallbacks(mRegisterCallbacks);
                    mRetryHandler.postDelayed(mRegisterCallbacks, IMS_SERVICE_RETRY_TIMEOUT_MS);
                }
            }
        }
    }

    public void handleImsServiceUp() {
        if(mPublication != null) {
            mPublication.handleImsServiceUp();
        }
    }

    public void handleImsServiceDown() {
        mAssociatedSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if(mPublication != null) {
            mPublication.handleImsServiceDown();
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.debug("RcsService onStartCommand");

        return super.onStartCommand(intent, flags, startId);
    }

    /**
      * Cleans up when the service is destroyed
      */
    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mObserver);
        getContentResolver().unregisterContentObserver(mSiminfoSettingObserver);
        getSystemService(SubscriptionManager.class)
                .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        mRcsStackAdaptor.finish();
        mPublication.finish();
        mPublication = null;
        mSubscriber = null;

        logger.debug("RcsService onDestroy");
        super.onDestroy();
    }

    public PresencePublication getPublication() {
        return mPublication;
    }

    IRcsPresence.Stub mIRcsPresenceImpl = new IRcsPresence.Stub() {
        /**
         * Asyncrhonously request the latest capability for a given contact list.
         * The result will be saved to DB directly if the contactNumber can be found in DB.
         * And then send intent com.android.ims.presence.CAPABILITY_STATE_CHANGED to notify it.
         * @param contactsNumber the contact list which will request capability.
         *                       Currently only support phone number.
         * @param listener the listener to get the response.
         * @return the resultCode which is defined by ResultCode.
         * @note framework uses only.
         * @hide
         */
        public int requestCapability(List<String> contactsNumber,
            IRcsPresenceListener listener){
            logger.debug("calling requestCapability");
            if(mSubscriber == null){
                logger.debug("requestCapability, mPresenceSubscriber == null");
                return ResultCode.ERROR_SERVICE_NOT_AVAILABLE;
            }

            return mSubscriber.requestCapability(contactsNumber, listener);
         }

        /**
         * Asyncrhonously request the latest presence for a given contact.
         * The result will be saved to DB directly if it can be found in DB. And then send intent
         * com.android.ims.presence.AVAILABILITY_STATE_CHANGED to notify it.
         * @param contactNumber the contact which will request available.
         *                       Currently only support phone number.
         * @param listener the listener to get the response.
         * @return the resultCode which is defined by ResultCode.
         * @note framework uses only.
         * @hide
         */
        public int requestAvailability(String contactNumber, IRcsPresenceListener listener){
            if(mSubscriber == null){
                logger.error("requestAvailability, mPresenceSubscriber is null");
                return ResultCode.ERROR_SERVICE_NOT_AVAILABLE;
            }

            // check availability cache (in RAM).
            return mSubscriber.requestAvailability(contactNumber, listener, false);
        }

        /**
         * Same as requestAvailability. but requestAvailability will consider throttle to avoid too
         * fast call. Which means it will not send the request to network in next 60s for the same
         * request.
         * The error code SUBSCRIBE_TOO_FREQUENTLY will be returned under the case.
         * But for this funcation it will always send the request to network.
         *
         * @see IRcsPresenceListener
         * @see RcsManager.ResultCode
         * @see ResultCode.SUBSCRIBE_TOO_FREQUENTLY
         */
        public int requestAvailabilityNoThrottle(String contactNumber,
                IRcsPresenceListener listener) {
            if(mSubscriber == null){
                logger.error("requestAvailabilityNoThrottle, mPresenceSubscriber is null");
                return ResultCode.ERROR_SERVICE_NOT_AVAILABLE;
            }

            // check availability cache (in RAM).
            return mSubscriber.requestAvailability(contactNumber, listener, true);
        }

        public int getPublishState() throws RemoteException {
            return mPublication.getPublishState();
        }
    };

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    /**
     * Receives notifications when Mobile data is enabled or disabled.
     */
    private class MobileDataContentObserver extends ContentObserver {
        public MobileDataContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(final boolean selfChange) {
            boolean enabled = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;
            logger.debug("Mobile data enabled status: " + (enabled ? "ON" : "OFF"));
            onMobileDataEnabled(enabled);
        }
    }

    /** Observer to get notified when Mobile data enabled status changes */
    private MobileDataContentObserver mObserver;

    private void onMobileDataEnabled(final boolean enabled) {
        logger.debug("Enter onMobileDataEnabled: " + enabled);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    if(mPublication != null){
                        mPublication.onMobileDataChanged(enabled);
                        return;
                    }
                }catch(Exception e){
                    logger.error("Exception onMobileDataEnabled:", e);
                }
            }
        }, "onMobileDataEnabled thread");

        thread.start();
    }


    private SimInfoContentObserver mSiminfoSettingObserver;

    /**
     * Receives notifications when the TelephonyProvider is changed.
     */
    private class SimInfoContentObserver extends ContentObserver {
        public SimInfoContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(final boolean selfChange) {
            if (mAssociatedSubscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return;
            }
            ImsMmTelManager ims = ImsMmTelManager.createForSubscriptionId(mAssociatedSubscription);
            try {
                boolean enabled = ims.isVtSettingEnabled();
                logger.debug("vt enabled status: " + (enabled ? "ON" : "OFF"));
                onVtEnabled(enabled);
            } catch (Exception e) {
                logger.info("Exception getting VT status for sub:" + mAssociatedSubscription
                        + ", Exception = " + e.getMessage());
            }
        }
    }

    private void onVtEnabled(boolean enabled) {
        if(mPublication != null){
            mPublication.onVtEnabled(enabled);
        }
    }

    private final IRcsService.Stub mBinder = new IRcsService.Stub() {
        /**
         * return true if the rcs service is ready for use.
         */
        public boolean isRcsServiceAvailable(){
            logger.debug("calling isRcsServiceAvailable");
            if(mRcsStackAdaptor == null){
                return false;
            }

            return mRcsStackAdaptor.isImsEnableState();
        }

        /**
         * Gets the presence interface.
         *
         * @see IRcsPresence
         */
        public IRcsPresence getRcsPresenceInterface(){
            return mIRcsPresenceImpl;
        }
    };

    private RegistrationManager.RegistrationCallback mImsRegistrationCallback
            = new RegistrationManager.RegistrationCallback() {

        @Override
        public void onRegistered(int imsTransportType) {
            logger.debug("onImsConnected imsTransportType=" + imsTransportType);
            mNetworkRegistrationType = imsTransportType;
            if(mPublication != null) {
                mPublication.onImsConnected();
            }
        }

        @Override
        public void onUnregistered(ImsReasonInfo info) {
            logger.debug("onImsDisconnected");
            mNetworkRegistrationType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
            if(mPublication != null) {
                mPublication.onImsDisconnected();
            }
        }
    };

    private ImsMmTelManager.CapabilityCallback mCapabilityCallback
            = new ImsMmTelManager.CapabilityCallback() {

        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities capabilities) {
            mPublication.onFeatureCapabilityChanged(mNetworkRegistrationType, capabilities);
        }
    };
}

