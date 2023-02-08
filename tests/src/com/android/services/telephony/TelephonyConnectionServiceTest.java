/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.services.telephony;

import static android.telephony.DisconnectCause.EMERGENCY_PERM_FAILURE;
import static android.telephony.DisconnectCause.EMERGENCY_TEMP_FAILURE;
import static android.telephony.DisconnectCause.NOT_DISCONNECTED;
import static android.telephony.DomainSelectionService.SELECTOR_TYPE_CALLING;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_CS;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;
import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;
import static android.telephony.ims.ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL;

import static com.android.internal.telephony.RILConstants.GSM_PHONE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.DomainSelectionService;
import android.telephony.RadioAccessFamily;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.ims.ImsManager;
import com.android.internal.telecom.IConnectionService;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneInternalInterface.DialArgs;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.data.PhoneSwitcher;
import com.android.internal.telephony.domainselection.DomainSelectionConnection;
import com.android.internal.telephony.domainselection.DomainSelectionResolver;
import com.android.internal.telephony.domainselection.EmergencyCallDomainSelectionConnection;
import com.android.internal.telephony.domainselection.NormalCallDomainSelectionConnection;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.internal.telephony.emergency.EmergencyStateTracker;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.imsphone.ImsPhone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Unit tests for TelephonyConnectionService.
 */

@RunWith(AndroidJUnit4.class)
public class TelephonyConnectionServiceTest extends TelephonyTestBase {
    /**
     * Unlike {@link TestTelephonyConnection}, a bare minimal {@link TelephonyConnection} impl
     * that does not try to configure anything.
     */
    public static class SimpleTelephonyConnection extends TelephonyConnection {
        public boolean wasDisconnected = false;

        @Override
        public TelephonyConnection cloneConnection() {
            return null;
        }

        @Override
        public void hangup(int telephonyDisconnectCode) {
            wasDisconnected = true;
        }
    }

    private static final long TIMEOUT_MS = 100;
    private static final int SLOT_0_PHONE_ID = 0;
    private static final int SLOT_1_PHONE_ID = 1;

    private static final ComponentName TEST_COMPONENT_NAME = new ComponentName(
            "com.android.phone.tests", TelephonyConnectionServiceTest.class.getName());
    private static final String TEST_ACCOUNT_ID1 = "id1";
    private static final String TEST_ACCOUNT_ID2 = "id2";
    private static final PhoneAccountHandle PHONE_ACCOUNT_HANDLE_1 = new PhoneAccountHandle(
            TEST_COMPONENT_NAME, TEST_ACCOUNT_ID1);
    private static final PhoneAccountHandle PHONE_ACCOUNT_HANDLE_2 = new PhoneAccountHandle(
            TEST_COMPONENT_NAME, TEST_ACCOUNT_ID2);
    private static final Uri TEST_ADDRESS = Uri.parse("tel:+16505551212");
    private static final String TELECOM_CALL_ID1 = "TC1";
    private static final String TEST_EMERGENCY_NUMBER = "911";
    private android.telecom.Connection mConnection;

    @Mock TelephonyConnectionService.TelephonyManagerProxy mTelephonyManagerProxy;
    @Mock TelephonyConnectionService.SubscriptionManagerProxy mSubscriptionManagerProxy;
    @Mock TelephonyConnectionService.PhoneFactoryProxy mPhoneFactoryProxy;
    @Mock DeviceState mDeviceState;
    @Mock TelephonyConnectionService.PhoneSwitcherProxy mPhoneSwitcherProxy;
    @Mock TelephonyConnectionService.PhoneNumberUtilsProxy mPhoneNumberUtilsProxy;
    @Mock TelephonyConnectionService.PhoneUtilsProxy mPhoneUtilsProxy;
    @Mock TelephonyConnectionService.DisconnectCauseFactory mDisconnectCauseFactory;
    @Mock Handler mMockHandler;
    @Mock EmergencyNumberTracker mEmergencyNumberTracker;
    @Mock PhoneSwitcher mPhoneSwitcher;
    @Mock RadioOnHelper mRadioOnHelper;
    @Mock ServiceStateTracker mSST;
    @Mock Call mCall;
    @Mock Call mCall2;
    @Mock com.android.internal.telephony.Connection mInternalConnection;
    @Mock com.android.internal.telephony.Connection mInternalConnection2;
    @Mock DomainSelectionResolver mDomainSelectionResolver;
    @Mock EmergencyCallDomainSelectionConnection mEmergencyCallDomainSelectionConnection;
    @Mock NormalCallDomainSelectionConnection mNormalCallDomainSelectionConnection;
    @Mock ImsPhone mImsPhone;
    private EmergencyStateTracker mEmergencyStateTracker;
    private Phone mPhone0;
    private Phone mPhone1;

    private static class TestTelephonyConnectionService extends TelephonyConnectionService {

        private final Context mContext;

        TestTelephonyConnectionService(Context context) {
            mContext = context;
        }

        @Override
        public void onCreate() {
            // attach test context.
            attachBaseContext(mContext);
            super.onCreate();
        }
    }

    private TelephonyConnectionService mTestConnectionService;
    private IConnectionService.Stub mBinderStub;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTestConnectionService = new TestTelephonyConnectionService(mContext);
        mTestConnectionService.setPhoneFactoryProxy(mPhoneFactoryProxy);
        mTestConnectionService.setSubscriptionManagerProxy(mSubscriptionManagerProxy);
        // Set configurations statically
        doReturn(false).when(mDeviceState).shouldCheckSimStateBeforeOutgoingCall(any());
        mTestConnectionService.setPhoneSwitcherProxy(mPhoneSwitcherProxy);
        doReturn(mPhoneSwitcher).when(mPhoneSwitcherProxy).getPhoneSwitcher();
        when(mPhoneNumberUtilsProxy.convertToEmergencyNumber(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        mTestConnectionService.setPhoneNumberUtilsProxy(mPhoneNumberUtilsProxy);
        mTestConnectionService.setPhoneUtilsProxy(mPhoneUtilsProxy);
        mTestConnectionService.setDeviceState(mDeviceState);
        mTestConnectionService.setRadioOnHelper(mRadioOnHelper);
        doReturn(new DisconnectCause(DisconnectCause.UNKNOWN)).when(mDisconnectCauseFactory)
                .toTelecomDisconnectCause(anyInt(), any());
        doReturn(new DisconnectCause(DisconnectCause.UNKNOWN)).when(mDisconnectCauseFactory)
                .toTelecomDisconnectCause(anyInt(), any(), anyInt());
        mTestConnectionService.setDisconnectCauseFactory(mDisconnectCauseFactory);
        mTestConnectionService.onCreate();
        mTestConnectionService.setTelephonyManagerProxy(mTelephonyManagerProxy);
        replaceInstance(TelephonyConnectionService.class, "mDomainSelectionResolver",
                mTestConnectionService, mDomainSelectionResolver);
        mEmergencyStateTracker = Mockito.mock(EmergencyStateTracker.class);
        replaceInstance(TelephonyConnectionService.class, "mEmergencyStateTracker",
                mTestConnectionService, mEmergencyStateTracker);
        doReturn(CompletableFuture.completedFuture(NOT_DISCONNECTED))
                .when(mEmergencyStateTracker)
                .startEmergencyCall(any(), anyString(), eq(false));
        replaceInstance(TelephonyConnectionService.class,
                "mDomainSelectionMainExecutor", mTestConnectionService, getExecutor());
        doReturn(false).when(mDomainSelectionResolver).isDomainSelectionSupported();
        doReturn(null).when(mDomainSelectionResolver).getDomainSelectionConnection(
                any(), anyInt(), anyBoolean());
        mBinderStub = (IConnectionService.Stub) mTestConnectionService.onBind(null);
    }

    @After
    public void tearDown() throws Exception {
        mTestConnectionService = null;
        super.tearDown();
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is IN_SERVICE, Slot 1 is OUT_OF_SERVICE (emergency calls only)
     * - Slot 1 is in Emergency SMS Mode
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone
     */
    @Test
    @SmallTest
    public void testEmergencySmsModeSimEmergencyOnly() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                true /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setEmergencySmsMode(slot1Phone, true);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is IN_SERVICE, Slot 1 is OUT_OF_SERVICE
     * - Slot 1 is in Emergency SMS Mode
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone
     */
    @Test
    @SmallTest
    public void testEmergencySmsModeSimOutOfService() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setEmergencySmsMode(slot1Phone, true);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Users default Voice SIM choice is IN_SERVICE
     *
     * Result: getFirstPhoneForEmergencyCall returns the default Voice SIM choice.
     */
    @Test
    @SmallTest
    public void testDefaultVoiceSimInService() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                true /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Users default data SIM choice is OUT_OF_SERVICE (emergency calls only)
     *
     * Result: getFirstPhoneForEmergencyCall returns the default data SIM choice.
     */
    @Test
    @SmallTest
    public void testDefaultDataSimEmergencyOnly() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                true /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setDefaultDataPhoneId(SLOT_1_PHONE_ID);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Users default data SIM choice is OUT_OF_SERVICE
     *
     * Result: getFirstPhoneForEmergencyCall does not return the default data SIM choice.
     */
    @Test
    @SmallTest
    public void testDefaultDataSimOutOfService() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setDefaultDataPhoneId(SLOT_1_PHONE_ID);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is OUT_OF_SERVICE, Slot 1 is OUT_OF_SERVICE (emergency calls only)
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone
     */
    @Test
    @SmallTest
    public void testSlot1EmergencyOnly() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                true /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is OUT_OF_SERVICE, Slot 1 is IN_SERVICE
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone
     */
    @Test
    @SmallTest
    public void testSlot1InService() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is PUK locked, Slot 1 is ready
     * - Slot 0 is LTE capable, Slot 1 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone. Although Slot 0 is more
     * capable, it is locked, so use the other slot.
     */
    @Test
    @SmallTest
    public void testSlot0PukLocked() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        // Set Slot 0 to be PUK locked
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_PUK_REQUIRED);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Slot 0 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_GSM);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 0 is PIN locked, Slot 1 is ready
     * - Slot 0 is LTE capable, Slot 1 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone. Although Slot 0 is more
     * capable, it is locked, so use the other slot.
     */
    @Test
    @SmallTest
    public void testSlot0PinLocked() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        // Set Slot 0 to be PUK locked
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_PIN_REQUIRED);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Slot 0 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_GSM);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 1 is PUK locked, Slot 0 is ready
     * - Slot 1 is LTE capable, Slot 0 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone. Although Slot 1 is more
     * capable, it is locked, so use the other slot.
     */
    @Test
    @SmallTest
    public void testSlot1PukLocked() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        // Set Slot 1 to be PUK locked
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_PUK_REQUIRED);
        // Make Slot 1 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_GSM);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 1 is PIN locked, Slot 0 is ready
     * - Slot 1 is LTE capable, Slot 0 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone. Although Slot 1 is more
     * capable, it is locked, so use the other slot.
     */
    @Test
    @SmallTest
    public void testSlot1PinLocked() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        // Set Slot 1 to be PUK locked
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_PIN_REQUIRED);
        // Make Slot 1 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_GSM);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, only slot 1 inserted and PUK locked
     * - slot 1 has higher capabilities
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone because it is the only one
     * with a SIM inserted (even if it is PUK locked)
     */
    @Test
    @SmallTest
    public void testSlot1PinLockedAndSlot0Absent() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_PIN_REQUIRED);
        // Slot 1 has more capabilities
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_GSM);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 1 is LTE capable, Slot 0 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone because it is more capable
     */
    @Test
    @SmallTest
    public void testSlot1HigherCapablity() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Slot 1 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_GSM);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Slot 1 is GSM/LTE capable, Slot 0 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone because it has more
     * capabilities.
     */
    @Test
    @SmallTest
    public void testSlot1MoreCapabilities() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Slot 1 more capable
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone,
                RadioAccessFamily.RAF_GSM | RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Both SIMs PUK Locked
     * - Slot 0 is LTE capable, Slot 1 is GSM capable
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone because it is more capable,
     * ignoring that both SIMs are PUK locked.
     */
    @Test
    @SmallTest
    public void testSlot0MoreCapableBothPukLocked() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_PUK_REQUIRED);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_PUK_REQUIRED);
        // Make Slot 0 higher capability
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_GSM);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, two slots with SIMs inserted
     * - Both SIMs have the same capability
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone because it is the first slot.
     */
    @Test
    @SmallTest
    public void testEqualCapabilityTwoSimsInserted() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, only slot 0 inserted
     * - Both SIMs have the same capability
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone because it is the only one
     * with a SIM inserted
     */
    @Test
    @SmallTest
    public void testEqualCapabilitySim0Inserted() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, only slot 1 inserted
     * - Both SIMs have the same capability
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone because it is the only one
     * with a SIM inserted
     */
    @Test
    @SmallTest
    public void testEqualCapabilitySim1Inserted() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device with one ESIM, only slot 1 inserted has PSIM inserted
     * - Both phones have the same capability
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone because it is the only one
     * with a SIM inserted
     */
    @Test
    @SmallTest
    public void testEqualCapabilitySim1Inserted_WithOneEsim() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        when(slot0Phone.getSubId()).thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_LTE);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, no SIMs inserted
     * - SIM 1 has the higher capability
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 1 phone, since it is a higher
     * capability
     */
    @Test
    @SmallTest
    public void testSim1HigherCapabilityNoSimsInserted() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_GSM);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_LTE);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot1Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, no SIMs inserted
     * - Both SIMs have the same capability (Unknown)
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone, since it is the first slot.
     */
    @Test
    @SmallTest
    public void testEqualCapabilityNoSimsInserted() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        // Make Capability the same
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_UNKNOWN);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_UNKNOWN);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, no SIMs inserted (one ESIM)
     * - Both SIMs have the same capability (Unknown)
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone, since it is the first slot.
     */
    @Test
    @SmallTest
    public void testEqualCapabilityNoSimsInserted_WithOneESim() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_ABSENT);
        when(slot1Phone.getSubId()).thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Capability the samesvim
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_UNKNOWN);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_UNKNOWN);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * Prerequisites:
     * - MSIM Device, both ESIMS (no profile activated)
     * - Both phones have the same capability (Unknown)
     *
     * Result: getFirstPhoneForEmergencyCall returns the slot 0 phone, since it is the first slot.
     */
    @Test
    @SmallTest
    public void testEqualCapabilityNoSimsInserted_WithTwoESims() {
        Phone slot0Phone = makeTestPhone(SLOT_0_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
            false /*isEmergencyOnly*/);
        setDefaultPhone(slot0Phone);
        setupDeviceConfig(slot0Phone, slot1Phone, SLOT_0_PHONE_ID);
        when(slot0Phone.getSubId()).thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setPhoneSlotState(SLOT_0_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        when(slot1Phone.getSubId()).thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        setPhoneSlotState(SLOT_1_PHONE_ID, TelephonyManager.SIM_STATE_READY);
        // Make Capability the sames
        setPhoneRadioAccessFamily(slot0Phone, RadioAccessFamily.RAF_UNKNOWN);
        setPhoneRadioAccessFamily(slot1Phone, RadioAccessFamily.RAF_UNKNOWN);

        Phone resultPhone = mTestConnectionService.getFirstPhoneForEmergencyCall();

        assertEquals(slot0Phone, resultPhone);
    }

    /**
     * The modem has returned a temporary error when placing an emergency call on a phone with one
     * SIM slot.
     *
     * Verify that dial is called on the same phone again when retryOutgoingOriginalConnection is
     * called.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialTempFailOneSlot() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        List<Phone> phones = new ArrayList<>(1);
        phones.add(slot0Phone);
        setPhones(phones);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);

        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), false /*isPermanentFailure*/);

        // We never need to be notified in telecom that the PhoneAccount has changed, because it
        // was redialed on the same slot
        assertEquals(0, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot0Phone).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    /**
     * The modem has returned a permanent failure when placing an emergency call on a phone with one
     * SIM slot.
     *
     * Verify that the connection is set to disconnected with an error disconnect cause and dial is
     * not called.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialPermFailOneSlot() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        List<Phone> phones = new ArrayList<>(1);
        phones.add(slot0Phone);
        setPhones(phones);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);

        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), true /*isPermanentFailure*/);

        // We never need to be notified in telecom that the PhoneAccount has changed, because it
        // was never redialed
        assertEquals(0, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot0Phone, never()).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
        assertEquals(c.getState(), android.telecom.Connection.STATE_DISCONNECTED);
        assertEquals(c.getDisconnectCause().getCode(), DisconnectCause.ERROR);
    }

    /**
     * The modem has returned a temporary failure when placing an emergency call on a phone with two
     * SIM slots.
     *
     * Verify that the emergency call is dialed on the other slot and telecom is notified of the new
     * PhoneAccount.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialTempFailTwoSlot() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setPhonesDialConnection(slot1Phone, c.getOriginalConnection());
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        List<Phone> phones = new ArrayList<>(2);
        phones.add(slot0Phone);
        phones.add(slot1Phone);
        setPhones(phones);
        doReturn(PHONE_ACCOUNT_HANDLE_1).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot0Phone);
        doReturn(PHONE_ACCOUNT_HANDLE_2).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot1Phone);

        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), false /*isPermanentFailure*/);

        // The cache should still contain all of the Phones, since it was a temporary failure.
        assertEquals(2, mTestConnectionService.mEmergencyRetryCache.second.size());
        // We need to be notified in Telecom that the PhoneAccount has changed, because it was
        // redialed on another slot
        assertEquals(1, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot1Phone).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    /**
     * The modem has returned a temporary failure when placing an emergency call on a phone with two
     * SIM slots.
     *
     * Verify that the emergency call is dialed on the other slot and telecom is notified of the new
     * PhoneAccount.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialPermFailTwoSlot() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setPhonesDialConnection(slot1Phone, c.getOriginalConnection());
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        List<Phone> phones = new ArrayList<>(2);
        phones.add(slot0Phone);
        phones.add(slot1Phone);
        setPhones(phones);
        doReturn(PHONE_ACCOUNT_HANDLE_1).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot0Phone);
        doReturn(PHONE_ACCOUNT_HANDLE_2).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot1Phone);

        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), true /*isPermanentFailure*/);

        // The cache should only contain the slot1Phone.
        assertEquals(1, mTestConnectionService.mEmergencyRetryCache.second.size());
        // We need to be notified in Telecom that the PhoneAccount has changed, because it was
        // redialed on another slot
        assertEquals(1, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot1Phone).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    /**
     * The modem has returned a temporary failure twice while placing an emergency call on a phone
     * with two SIM slots.
     *
     * Verify that the emergency call is dialed on slot 1 and then on slot 0 and telecom is
     * notified of this twice.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialTempFailTwoSlot_twoFailure() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setPhonesDialConnection(slot1Phone, c.getOriginalConnection());
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        List<Phone> phones = new ArrayList<>(2);
        phones.add(slot0Phone);
        phones.add(slot1Phone);
        setPhones(phones);
        doReturn(PHONE_ACCOUNT_HANDLE_1).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot0Phone);
        doReturn(PHONE_ACCOUNT_HANDLE_2).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot1Phone);

        // First Temporary failure
        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), false /*isPermanentFailure*/);
        // Set the Phone to the new phone that was just used to dial.
        c.setMockPhone(slot1Phone);
        // The cache should still contain all of the Phones, since it was a temporary failure.
        assertEquals(2, mTestConnectionService.mEmergencyRetryCache.second.size());
        // Make sure slot 1 is next in the queue.
        assertEquals(slot1Phone, mTestConnectionService.mEmergencyRetryCache.second.peek());
        // Second Temporary failure
        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), false /*isPermanentFailure*/);
        // Set the Phone to the new phone that was just used to dial.
        c.setMockPhone(slot0Phone);
        // The cache should still contain all of the Phones, since it was a temporary failure.
        assertEquals(2, mTestConnectionService.mEmergencyRetryCache.second.size());
        // Make sure slot 0 is next in the queue.
        assertEquals(slot0Phone, mTestConnectionService.mEmergencyRetryCache.second.peek());

        // We need to be notified in Telecom that the PhoneAccount has changed, because it was
        // redialed on another slot
        assertEquals(2, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot0Phone).dial(anyString(), any(), any());
            verify(slot1Phone).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    /**
     * The modem has returned a permanent failure twice while placing an emergency call on a phone
     * with two SIM slots.
     *
     * Verify that the emergency call is dialed on slot 1 and then disconnected and telecom is
     * notified of the change to slot 1.
     */
    @Test
    @SmallTest
    public void testRetryOutgoingOriginalConnection_redialPermFailTwoSlot_twoFailure() {
        TestTelephonyConnection c = new TestTelephonyConnection();
        Phone slot0Phone = c.getPhone();
        when(slot0Phone.getPhoneId()).thenReturn(SLOT_0_PHONE_ID);
        Phone slot1Phone = makeTestPhone(SLOT_1_PHONE_ID, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        setPhonesDialConnection(slot1Phone, c.getOriginalConnection());
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        List<Phone> phones = new ArrayList<>(2);
        phones.add(slot0Phone);
        phones.add(slot1Phone);
        setPhones(phones);
        doReturn(PHONE_ACCOUNT_HANDLE_1).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot0Phone);
        doReturn(PHONE_ACCOUNT_HANDLE_2).when(mPhoneUtilsProxy).makePstnPhoneAccountHandle(
                slot1Phone);

        // First Permanent failure
        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), true /*isPermanentFailure*/);
        // Set the Phone to the new phone that was just used to dial.
        c.setMockPhone(slot1Phone);
        // The cache should only contain one phone
        assertEquals(1, mTestConnectionService.mEmergencyRetryCache.second.size());
        // Make sure slot 1 is next in the queue.
        assertEquals(slot1Phone, mTestConnectionService.mEmergencyRetryCache.second.peek());
        // Second Permanent failure
        mTestConnectionService.retryOutgoingOriginalConnection(c,
                c.getPhone(), true /*isPermanentFailure*/);
        // The cache should be empty
        assertEquals(true, mTestConnectionService.mEmergencyRetryCache.second.isEmpty());

        assertEquals(c.getState(), android.telecom.Connection.STATE_DISCONNECTED);
        assertEquals(c.getDisconnectCause().getCode(), DisconnectCause.ERROR);
        // We need to be notified in Telecom that the PhoneAccount has changed, because it was
        // redialed on another slot
        assertEquals(1, c.getNotifyPhoneAccountChangedCount());
        try {
            verify(slot1Phone).dial(anyString(), any(), any());
            verify(slot0Phone, never()).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    @Test
    @SmallTest
    public void testSuppServiceNotification() {
        TestTelephonyConnection c = new TestTelephonyConnection();

        // We need to set the original connection to cause the supp service notification
        // registration to occur.
        Phone phone = c.getPhone();
        c.setOriginalConnection(c.getOriginalConnection());
        doReturn(mContext).when(phone).getContext();

        // When the registration occurs, we'll capture the handler and message so we can post our
        // own messages to it.
        ArgumentCaptor<Handler> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
        ArgumentCaptor<Integer> messageCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(phone).registerForSuppServiceNotification(handlerCaptor.capture(),
                messageCaptor.capture(), any());
        Handler handler = handlerCaptor.getValue();
        int message = messageCaptor.getValue();

        // With the handler and message now known, we'll post a supp service notification.
        AsyncResult result = getSuppServiceNotification(
                SuppServiceNotification.NOTIFICATION_TYPE_CODE_1,
                SuppServiceNotification.CODE_1_CALL_FORWARDED);
        handler.obtainMessage(message, result).sendToTarget();
        waitForHandlerAction(handler, TIMEOUT_MS);

        assertTrue(c.getLastConnectionEvents().contains(TelephonyManager.EVENT_CALL_FORWARDED));

        // With the handler and message now known, we'll post a supp service notification.
        result = getSuppServiceNotification(
                SuppServiceNotification.NOTIFICATION_TYPE_CODE_1,
                SuppServiceNotification.CODE_1_CALL_IS_WAITING);
        handler.obtainMessage(message, result).sendToTarget();
        waitForHandlerAction(handler, TIMEOUT_MS);

        // We we want the 3rd event since the forwarding one above sends 2.
        assertEquals(c.getLastConnectionEvents().get(2),
                TelephonyManager.EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION);
        Bundle extras = c.getLastConnectionEventExtras().get(2);
        assertEquals(SuppServiceNotification.NOTIFICATION_TYPE_CODE_1,
                extras.getInt(TelephonyManager.EXTRA_NOTIFICATION_TYPE));
        assertEquals(SuppServiceNotification.CODE_1_CALL_IS_WAITING,
                extras.getInt(TelephonyManager.EXTRA_NOTIFICATION_CODE));
    }

    /**
     * Test that the TelephonyConnectionService successfully performs a DDS switch before a call
     * when we are not roaming and the carrier only supports SUPL over the data plane.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial_carrierconfig_dds() {
        // Setup test to not support SUPL on the non-DDS subscription
        doReturn(true).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                null);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_DP_ONLY);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "150");

        Phone testPhone = setupConnectionServiceForDelayDial(
                false /* isRoaming */, false /* setOperatorName */, null /* operator long name*/,
                        null /* operator short name */, null /* operator numeric name */);
        verify(mPhoneSwitcher).overrideDefaultDataForEmergency(eq(0) /*phoneId*/ ,
                eq(150) /*extensionTime*/, any());
    }

    /**
     * Test that the TelephonyConnectionService successfully turns radio on before placing the
     * emergency call.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmerge_exitingApm_disconnected() {
        when(mDeviceState.isAirplaneModeOn(any())).thenReturn(true);
        Phone testPhone = setupConnectionServiceInApm();

        ArgumentCaptor<RadioOnStateListener.Callback> callback =
                ArgumentCaptor.forClass(RadioOnStateListener.Callback.class);
        verify(mRadioOnHelper).triggerRadioOnAndListen(callback.capture(), eq(true),
                eq(testPhone), eq(false));

        assertFalse(callback.getValue().isOkToCall(testPhone, ServiceState.STATE_OUT_OF_SERVICE));
        when(mSST.isRadioOn()).thenReturn(true);
        assertTrue(callback.getValue().isOkToCall(testPhone, ServiceState.STATE_OUT_OF_SERVICE));

        mConnection.setDisconnected(null);
        callback.getValue().onComplete(null, true);
        for (Phone phone : mPhoneFactoryProxy.getPhones()) {
            verify(phone).setRadioPower(true, false, false, true);
        }
    }

    /**
     * Test that the TelephonyConnectionService successfully turns radio on before placing the
     * emergency call.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_exitingApm_placeCall() {
        when(mDeviceState.isAirplaneModeOn(any())).thenReturn(true);
        Phone testPhone = setupConnectionServiceInApm();

        ArgumentCaptor<RadioOnStateListener.Callback> callback =
                ArgumentCaptor.forClass(RadioOnStateListener.Callback.class);
        verify(mRadioOnHelper).triggerRadioOnAndListen(callback.capture(), eq(true),
                eq(testPhone), eq(false));

        assertFalse(callback.getValue().isOkToCall(testPhone, ServiceState.STATE_OUT_OF_SERVICE));
        when(mSST.isRadioOn()).thenReturn(true);
        assertTrue(callback.getValue().isOkToCall(testPhone, ServiceState.STATE_OUT_OF_SERVICE));

        callback.getValue().onComplete(null, true);

        try {
            doAnswer(invocation -> null).when(mContext).startActivity(any());
            verify(testPhone).dial(anyString(), any(), any());
        } catch (CallStateException e) {
            // This shouldn't happen
            fail();
        }
    }

    /**
     * Test that the TelephonyConnectionService does not perform a DDS switch when the carrier
     * supports control-plane fallback.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial_nocarrierconfig() {
        // Setup test to not support SUPL on the non-DDS subscription
        doReturn(true).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                null);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_CP_FALLBACK);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "0");

        Phone testPhone = setupConnectionServiceForDelayDial(
                false /* isRoaming */, false /* setOperatorName */, null /* operator long name*/,
                        null /* operator short name */, null /* operator numeric name */);
        verify(mPhoneSwitcher, never()).overrideDefaultDataForEmergency(anyInt(), anyInt(), any());
    }

    /**
     * Test that the TelephonyConnectionService does not perform a DDS switch when the carrier
     * supports control-plane fallback.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial_supportsuplondds() {
        // If the non-DDS supports SUPL, don't switch data
        doReturn(false).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                null);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_DP_ONLY);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "0");

        Phone testPhone = setupConnectionServiceForDelayDial(
                false /* isRoaming */, false /* setOperatorName */, null /* operator long name*/,
                         null /* operator short name */, null /* operator numeric name */);
        verify(mPhoneSwitcher, never()).overrideDefaultDataForEmergency(anyInt(), anyInt(), any());
    }

    /**
     * Test that the TelephonyConnectionService does not perform a DDS switch when the carrier does
     * not support control-plane fallback CarrierConfig while roaming.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial_roaming_nocarrierconfig() {
        // Setup test to not support SUPL on the non-DDS subscription
        doReturn(true).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                null);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_DP_ONLY);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "0");

        Phone testPhone = setupConnectionServiceForDelayDial(
                true /* isRoaming */, false /* setOperatorName */, null /* operator long name*/,
                         null /* operator short name */, null /* operator numeric name */);
        verify(mPhoneSwitcher, never()).overrideDefaultDataForEmergency(anyInt(), anyInt(), any());
    }

    /**
     * Test that the TelephonyConnectionService does perform a DDS switch even though the carrier
     * supports control-plane fallback CarrierConfig and the roaming partner is configured to look
     * like a home network.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial_roamingcarrierconfig() {
        doReturn(true).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        // Setup voice roaming scenario
        String testRoamingOperator = "001001";
        // Setup test to not support SUPL on the non-DDS subscription
        String[] roamingPlmns = new String[1];
        roamingPlmns[0] = testRoamingOperator;
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                roamingPlmns);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_CP_FALLBACK);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "0");

        Phone testPhone = setupConnectionServiceForDelayDial(
                false /* isRoaming */, true /* setOperatorName */,
                        "TestTel" /* operator long name*/, "TestTel" /* operator short name */,
                                testRoamingOperator /* operator numeric name */);
        verify(mPhoneSwitcher).overrideDefaultDataForEmergency(eq(0) /*phoneId*/ ,
                eq(0) /*extensionTime*/, any());
    }

    /**
     * Test that the TelephonyConnectionService does perform a DDS switch even though the carrier
     * supports control-plane fallback CarrierConfig if we are roaming and the roaming partner is
     * configured to use data plane only SUPL.
     */
    @Test
    @SmallTest
    public void testCreateOutgoingEmergencyConnection_delayDial__roaming_roamingcarrierconfig() {
        // Setup test to not support SUPL on the non-DDS subscription
        doReturn(true).when(mDeviceState).isSuplDdsSwitchRequiredForEmergencyCall(any());
        // Setup voice roaming scenario
        String testRoamingOperator = "001001";
        String[] roamingPlmns = new String[1];
        roamingPlmns[0] = testRoamingOperator;
        getTestContext().getCarrierConfig(0 /*subId*/).putStringArray(
                CarrierConfigManager.Gps.KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY,
                roamingPlmns);
        getTestContext().getCarrierConfig(0 /*subId*/).putInt(
                CarrierConfigManager.Gps.KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                CarrierConfigManager.Gps.SUPL_EMERGENCY_MODE_TYPE_CP_FALLBACK);
        getTestContext().getCarrierConfig(0 /*subId*/).putString(
                CarrierConfigManager.Gps.KEY_ES_EXTENSION_SEC_STRING, "0");

        Phone testPhone = setupConnectionServiceForDelayDial(
                false /* isRoaming */, true /* setOperatorName */,
                        "TestTel" /* operator long name*/, "TestTel" /* operator short name */,
                                testRoamingOperator /* operator numeric name */);
        verify(mPhoneSwitcher).overrideDefaultDataForEmergency(eq(0) /*phoneId*/ ,
                eq(0) /*extensionTime*/, any());
    }

    /**
     * Verifies for an incoming call on the same SIM that we don't set
     * {@link android.telecom.Connection#EXTRA_ANSWERING_DROPS_FG_CALL} on the incoming call extras.
     * @throws Exception
     */
    @Test
    @SmallTest
    public void testIncomingDoesntRequestDisconnect() throws Exception {
        setupForCallTest();

        mBinderStub.createConnection(PHONE_ACCOUNT_HANDLE_1, "TC@1",
                new ConnectionRequest(PHONE_ACCOUNT_HANDLE_1, Uri.parse("tel:16505551212"),
                        new Bundle()),
                true, false, null);
        waitForHandlerAction(mTestConnectionService.getHandler(), TIMEOUT_MS);
        assertEquals(1, mTestConnectionService.getAllConnections().size());

        // Make sure the extras do not indicate that it answering will disconnect another call.
        android.telecom.Connection connection = (android.telecom.Connection)
                mTestConnectionService.getAllConnections().toArray()[0];
        assertFalse(connection.getExtras() != null && connection.getExtras().containsKey(
                android.telecom.Connection.EXTRA_ANSWERING_DROPS_FG_CALL));
    }

    /**
     * Verifies where there is another call on the same sub, we don't set
     * {@link android.telecom.Connection#EXTRA_ANSWERING_DROPS_FG_CALL} on the incoming call extras.
     * @throws Exception
     */
    @Test
    @SmallTest
    public void testSecondCallSameSubWontDisconnect() throws Exception {
        // Previous test gets us into a good enough state
        testIncomingDoesntRequestDisconnect();

        when(mCall.getState()).thenReturn(Call.State.ACTIVE);
        when(mCall2.getState()).thenReturn(Call.State.WAITING);
        when(mCall2.getLatestConnection()).thenReturn(mInternalConnection2);
        when(mPhone0.getRingingCall()).thenReturn(mCall2);

        mBinderStub.createConnection(PHONE_ACCOUNT_HANDLE_1, "TC@2",
                new ConnectionRequest(PHONE_ACCOUNT_HANDLE_1, Uri.parse("tel:16505551213"),
                        new Bundle()),
                true, false, null);
        waitForHandlerAction(mTestConnectionService.getHandler(), TIMEOUT_MS);
        assertEquals(2, mTestConnectionService.getAllConnections().size());

        // None of the connections should have the extra set.
        assertEquals(0, mTestConnectionService.getAllConnections().stream()
                .filter(c -> c.getExtras() != null && c.getExtras().containsKey(
                        android.telecom.Connection.EXTRA_ANSWERING_DROPS_FG_CALL))
                .count());
    }

    /**
     * Verifies where there is another call on a different sub, we set
     * {@link android.telecom.Connection#EXTRA_ANSWERING_DROPS_FG_CALL} on the incoming call extras.
     * @throws Exception
     */
    @Test
    @SmallTest
    public void testSecondCallDifferentSubWillDisconnect() throws Exception {
        // Previous test gets us into a good enough state
        testIncomingDoesntRequestDisconnect();

        when(mCall.getState()).thenReturn(Call.State.ACTIVE);
        when(mCall2.getState()).thenReturn(Call.State.WAITING);
        when(mCall2.getLatestConnection()).thenReturn(mInternalConnection2);
        // At this point the call is ringing on the second phone.
        when(mPhone0.getRingingCall()).thenReturn(null);
        when(mPhone1.getRingingCall()).thenReturn(mCall2);

        mBinderStub.createConnection(PHONE_ACCOUNT_HANDLE_2, "TC@2",
                new ConnectionRequest(PHONE_ACCOUNT_HANDLE_2, Uri.parse("tel:16505551213"),
                        new Bundle()),
                true, false, null);
        waitForHandlerAction(mTestConnectionService.getHandler(), TIMEOUT_MS);
        assertEquals(2, mTestConnectionService.getAllConnections().size());

        // The incoming connection should have the extra set.
        assertEquals(1, mTestConnectionService.getAllConnections().stream()
                .filter(c -> c.getExtras() != null && c.getExtras().containsKey(
                        android.telecom.Connection.EXTRA_ANSWERING_DROPS_FG_CALL))
                .count());
    }

    /**
     * For virtual DSDA-enabled devices, verifies where there is another call on the same sub, we
     * don't set {@link android.telecom.Connection#EXTRA_ANSWERING_DROPS_FG_CALL} on the incoming
     * call extras.
     * @throws Exception
     */
    @Test
    @SmallTest
    public void testSecondCallDifferentSubWontDisconnectForDsdaDevice() throws Exception {
        // Re-uses existing test for setup, then configures device as virtual DSDA for test duration
        testIncomingDoesntRequestDisconnect();
        when(mTelephonyManagerProxy.isConcurrentCallsPossible()).thenReturn(true);

        when(mCall.getState()).thenReturn(Call.State.ACTIVE);
        when(mCall2.getState()).thenReturn(Call.State.WAITING);
        when(mCall2.getLatestConnection()).thenReturn(mInternalConnection2);
        // At this point the call is ringing on the second phone.
        when(mPhone0.getRingingCall()).thenReturn(null);
        when(mPhone1.getRingingCall()).thenReturn(mCall2);

        mBinderStub.createConnection(PHONE_ACCOUNT_HANDLE_2, "TC@2",
                new ConnectionRequest(PHONE_ACCOUNT_HANDLE_2, Uri.parse("tel:16505551213"),
                        new Bundle()),
                true, false, null);
        waitForHandlerAction(mTestConnectionService.getHandler(), TIMEOUT_MS);
        assertEquals(2, mTestConnectionService.getAllConnections().size());

        // None of the connections should have the extra set.
        assertEquals(0, mTestConnectionService.getAllConnections().stream()
                .filter(c -> c.getExtras() != null && c.getExtras().containsKey(
                        android.telecom.Connection.EXTRA_ANSWERING_DROPS_FG_CALL))
                .count());
    }

    private static final PhoneAccountHandle SUB1_HANDLE = new PhoneAccountHandle(
            new ComponentName("test", "class"), "1");
    private static final PhoneAccountHandle SUB2_HANDLE = new PhoneAccountHandle(
            new ComponentName("test", "class"), "2");

    @Test
    @SmallTest
    public void testDontDisconnectSameSub() {
        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE, 0, false);
        tcs.add(tc1);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB1_HANDLE, mTelephonyManagerProxy);
        // Would've preferred to use mockito, but can't mock out TelephonyConnection/Connection
        // easily.
        assertFalse(tc1.wasDisconnected);
    }

    @Test
    @SmallTest
    public void testDontDisconnectEmergency() {
        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE, 0, true);
        tcs.add(tc1);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB2_HANDLE, mTelephonyManagerProxy);
        // Other call is an emergency call, so don't disconnect it.
        assertFalse(tc1.wasDisconnected);
    }

    @Test
    @SmallTest
    public void testDontDisconnectExternal() {
        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE,
                android.telecom.Connection.PROPERTY_IS_EXTERNAL_CALL, false);
        tcs.add(tc1);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB2_HANDLE, mTelephonyManagerProxy);
        // Other call is an external call, so don't disconnect it.
        assertFalse(tc1.wasDisconnected);
    }

    @Test
    @SmallTest
    public void testDisconnectDifferentSub() {
        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE, 0, false);
        tcs.add(tc1);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB2_HANDLE, mTelephonyManagerProxy);
        assertTrue(tc1.wasDisconnected);
    }

    @Test
    @SmallTest
    public void testDisconnectDifferentSubTwoCalls() {
        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE, 0, false);
        SimpleTelephonyConnection tc2 = createTestConnection(SUB1_HANDLE, 0, false);

        tcs.add(tc1);
        tcs.add(tc2);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB2_HANDLE, mTelephonyManagerProxy);
        assertTrue(tc1.wasDisconnected);
        assertTrue(tc2.wasDisconnected);
    }

    /**
     * Verifies that DSDA or virtual DSDA-enabled devices can support active non-emergency calls on
     * separate subs.
     */
    @Test
    @SmallTest
    public void testDontDisconnectDifferentSubForVirtualDsdaDevice() {
        when(mTelephonyManagerProxy.isConcurrentCallsPossible()).thenReturn(true);

        ArrayList<android.telecom.Connection> tcs = new ArrayList<>();
        SimpleTelephonyConnection tc1 = createTestConnection(SUB1_HANDLE, 0, false);
        tcs.add(tc1);
        TelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                tcs, SUB2_HANDLE, mTelephonyManagerProxy);
        assertFalse(tc1.wasDisconnected);
    }

    /**
     * Verifies that TelephonyManager is used to determine whether a connection is Emergency when
     * creating an outgoing connection.
     */
    @Test
    @SmallTest
    public void testIsEmergencyDeterminedByTelephonyManager() {
        ConnectionRequest connectionRequest = new ConnectionRequest.Builder()
                .setAccountHandle(PHONE_ACCOUNT_HANDLE_1)
                .setAddress(TEST_ADDRESS)
                .build();
        mConnection = mTestConnectionService.onCreateOutgoingConnection(
                PHONE_ACCOUNT_HANDLE_1, connectionRequest);

        verify(mTelephonyManagerProxy)
                .isCurrentEmergencyNumber(TEST_ADDRESS.getSchemeSpecificPart());
    }

    @Test
    public void testDomainSelectionCs() throws Exception {
        setupForCallTest();

        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(true));
        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));
        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionPs() throws Exception {
        setupForCallTest();

        int selectedDomain = DOMAIN_PS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(true));
        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));
        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionCsForTty() throws Exception {
        setupForCallTest();

        ImsManager imsManager = Mockito.mock(ImsManager.class);
        doReturn(false).when(imsManager).isNonTtyOrTtyOnVolteEnabled();
        replaceInstance(TelephonyConnectionService.class,
                "mImsManager", mTestConnectionService, imsManager);

        setupForDialForDomainSelection(mPhone0, DOMAIN_PS, true);

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mEmergencyStateTracker, times(1))
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));
        verify(mDomainSelectionResolver, times(0))
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(true));
        verify(mEmergencyCallDomainSelectionConnection, times(0))
                .createEmergencyConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(DOMAIN_CS, dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionRedialCs() throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int selectedDomain = DOMAIN_CS;

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mPhone0, selectedDomain, preciseDisconnectCause, disconnectCause, true);

        assertTrue(mTestConnectionService.maybeReselectDomain(c, preciseDisconnectCause, null));
        verify(mEmergencyCallDomainSelectionConnection).reselectDomain(any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        Connection nc = Mockito.mock(Connection.class);
        doReturn(nc).when(mPhone0).dial(anyString(), any(), any());

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionRedialPs() throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int selectedDomain = DOMAIN_PS;

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mPhone0, selectedDomain, preciseDisconnectCause, disconnectCause, true);

        assertTrue(mTestConnectionService.maybeReselectDomain(c, preciseDisconnectCause, null));
        verify(mEmergencyCallDomainSelectionConnection).reselectDomain(any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        Connection nc = Mockito.mock(Connection.class);
        doReturn(nc).when(mPhone0).dial(anyString(), any(), any());

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionNormalRoutingEmergencyNumber() throws Exception {
        setupForCallTest();
        int selectedDomain = DOMAIN_PS;

        EmergencyNumber emergencyNumber = new EmergencyNumber(TEST_EMERGENCY_NUMBER, "", "",
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                Collections.emptyList(),
                EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE,
                EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL);

        setupForDialForDomainSelection(mPhone0, selectedDomain, false);
        doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(anyString());
        doReturn(emergencyNumber).when(mEmergencyNumberTracker).getEmergencyNumber(anyString());

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(false));
        verify(mNormalCallDomainSelectionConnection).createNormalConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(
                selectedDomain, dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testDomainSelectionNormalToEmergencyCs() throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int eccCategory = EMERGENCY_SERVICE_CATEGORY_POLICE;
        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);
        doReturn(mPhone0).when(mImsPhone).getDefaultPhone();

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mImsPhone, selectedDomain, preciseDisconnectCause, disconnectCause, false);
        c.setEmergencyServiceCategory(eccCategory);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);

        ImsReasonInfo reasonInfo = new ImsReasonInfo(CODE_SIP_ALTERNATE_EMERGENCY_CALL, 0, null);
        assertTrue(mTestConnectionService.maybeReselectDomain(c,
                  preciseDisconnectCause, reasonInfo));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(true));
        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));
        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
        assertTrue(dialArgs.isEmergency);
        assertEquals(eccCategory, dialArgs.eccCategory);
    }

    @Test
    public void testDomainSelectionNormalToEmergencyPs() throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int eccCategory = EMERGENCY_SERVICE_CATEGORY_POLICE;
        int selectedDomain = DOMAIN_PS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);
        doReturn(mPhone0).when(mImsPhone).getDefaultPhone();

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mImsPhone, selectedDomain, preciseDisconnectCause, disconnectCause, false);
        c.setEmergencyServiceCategory(eccCategory);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);

        ImsReasonInfo reasonInfo = new ImsReasonInfo(CODE_SIP_ALTERNATE_EMERGENCY_CALL, 0, null);
        assertTrue(mTestConnectionService.maybeReselectDomain(c,
                  preciseDisconnectCause, reasonInfo));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(true));
        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));
        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(selectedDomain,
                dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
        assertTrue(dialArgs.isEmergency);
        assertEquals(eccCategory, dialArgs.eccCategory);
    }

    @Test
    public void testOnSelectionTerminatedPerm() throws Exception {
        setupForCallTest();

        doReturn(mEmergencyCallDomainSelectionConnection).when(mDomainSelectionResolver)
                .getDomainSelectionConnection(any(), anyInt(), eq(true));
        doReturn(mPhone0).when(mEmergencyCallDomainSelectionConnection).getPhone();
        doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(anyString());

        doReturn(true).when(mDomainSelectionResolver).isDomainSelectionSupported();
        doReturn(mImsPhone).when(mPhone0).getImsPhone();

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        ArgumentCaptor<DomainSelectionConnection.DomainSelectionConnectionCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        DomainSelectionConnection.DomainSelectionConnectionCallback.class);

        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(
                any(), callbackCaptor.capture());

        DomainSelectionConnection.DomainSelectionConnectionCallback callback =
                callbackCaptor.getValue();

        assertNotNull(callback);

        EmergencyCallDomainSelectionConnection ecdsc =
                Mockito.mock(EmergencyCallDomainSelectionConnection.class);
        doReturn(ecdsc).when(mDomainSelectionResolver)
                .getDomainSelectionConnection(any(), anyInt(), eq(true));

        callback.onSelectionTerminated(EMERGENCY_PERM_FAILURE);

        ArgumentCaptor<DomainSelectionService.SelectionAttributes> attrCaptor =
                ArgumentCaptor.forClass(
                        DomainSelectionService.SelectionAttributes.class);

        verify(ecdsc).createEmergencyConnection(attrCaptor.capture(), any());

        DomainSelectionService.SelectionAttributes attr = attrCaptor.getValue();

        assertEquals(mPhone1.getPhoneId(), attr.getSlotId());
    }

    @Test
    public void testOnSelectionTerminatedTemp() throws Exception {
        setupForCallTest();

        doReturn(mEmergencyCallDomainSelectionConnection).when(mDomainSelectionResolver)
                .getDomainSelectionConnection(any(), anyInt(), eq(true));
        doReturn(mPhone0).when(mEmergencyCallDomainSelectionConnection).getPhone();
        doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(anyString());

        doReturn(true).when(mDomainSelectionResolver).isDomainSelectionSupported();
        doReturn(mImsPhone).when(mPhone0).getImsPhone();

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        ArgumentCaptor<DomainSelectionConnection.DomainSelectionConnectionCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        DomainSelectionConnection.DomainSelectionConnectionCallback.class);

        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(
                any(), callbackCaptor.capture());

        DomainSelectionConnection.DomainSelectionConnectionCallback callback =
                callbackCaptor.getValue();

        assertNotNull(callback);

        EmergencyCallDomainSelectionConnection ecdsc =
                Mockito.mock(EmergencyCallDomainSelectionConnection.class);
        doReturn(ecdsc).when(mDomainSelectionResolver)
                .getDomainSelectionConnection(any(), anyInt(), eq(true));

        callback.onSelectionTerminated(EMERGENCY_TEMP_FAILURE);

        ArgumentCaptor<DomainSelectionService.SelectionAttributes> attrCaptor =
                ArgumentCaptor.forClass(
                        DomainSelectionService.SelectionAttributes.class);

        verify(ecdsc).createEmergencyConnection(attrCaptor.capture(), any());

        DomainSelectionService.SelectionAttributes attr = attrCaptor.getValue();

        assertEquals(mPhone1.getPhoneId(), attr.getSlotId());
    }

    @Test
    public void testDomainSelectionLocalHangupStartEmergencyCall() throws Exception {
        setupForCallTest();

        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);

        CompletableFuture<Integer> future = new CompletableFuture<>();
        doReturn(future).when(mEmergencyStateTracker)
                .startEmergencyCall(any(), anyString(), eq(false));

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));

        TelephonyConnection c = new TestTelephonyConnection();
        c.setTelecomCallId(TELECOM_CALL_ID1);

        // dialing is canceled
        mTestConnectionService.onLocalHangup(c);

        // startEmergencyCall has completed
        future.complete(NOT_DISCONNECTED);

        // verify that createEmergencyConnection is discarded
        verify(mEmergencyCallDomainSelectionConnection, times(0))
                .createEmergencyConnection(any(), any());
    }

    @Test
    public void testDomainSelectionLocalHangupCreateEmergencyConnection() throws Exception {
        setupForCallTest();

        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);

        CompletableFuture<Integer> future = new CompletableFuture<>();
        doReturn(future).when(mEmergencyCallDomainSelectionConnection)
                .createEmergencyConnection(any(), any());

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1,
                        TEST_EMERGENCY_NUMBER, TELECOM_CALL_ID1));

        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        TelephonyConnection c = new TestTelephonyConnection();
        c.setTelecomCallId(TELECOM_CALL_ID1);

        // dialing is canceled
        mTestConnectionService.onLocalHangup(c);

        // domain selection has completed
        future.complete(selectedDomain);

        // verify that dialing is discarded
        verify(mPhone0, times(0)).dial(anyString(), any(), any());
    }

    @Test
    public void testDomainSelectionRedialLocalHangupReselectDomain() throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int selectedDomain = DOMAIN_CS;

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mPhone0, selectedDomain, preciseDisconnectCause, disconnectCause, true);
        c.setTelecomCallId(TELECOM_CALL_ID1);

        CompletableFuture<Integer> future = new CompletableFuture<>();
        doReturn(future).when(mEmergencyCallDomainSelectionConnection)
                .reselectDomain(any());

        assertTrue(mTestConnectionService.maybeReselectDomain(c, preciseDisconnectCause, null));
        verify(mEmergencyCallDomainSelectionConnection).reselectDomain(any());

        // dialing is canceled
        mTestConnectionService.onLocalHangup(c);

        // domain selection has completed
        future.complete(selectedDomain);

        // verify that dialing is discarded
        verify(mPhone0, times(0)).dial(anyString(), any(), any());
    }

    @Test
    public void testDomainSelectionNormalToEmergencyLocalHangupStartEmergencyCall()
            throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int eccCategory = EMERGENCY_SERVICE_CATEGORY_POLICE;
        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);
        doReturn(mPhone0).when(mImsPhone).getDefaultPhone();

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mImsPhone, selectedDomain, preciseDisconnectCause, disconnectCause, false);
        c.setEmergencyServiceCategory(eccCategory);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        c.setTelecomCallId(TELECOM_CALL_ID1);

        CompletableFuture<Integer> future = new CompletableFuture<>();
        doReturn(future).when(mEmergencyStateTracker)
                .startEmergencyCall(any(), anyString(), eq(false));

        ImsReasonInfo reasonInfo = new ImsReasonInfo(CODE_SIP_ALTERNATE_EMERGENCY_CALL, 0, null);
        assertTrue(mTestConnectionService.maybeReselectDomain(c,
                  preciseDisconnectCause, reasonInfo));

        verify(mEmergencyStateTracker)
                .startEmergencyCall(eq(mPhone0), eq(TELECOM_CALL_ID1), eq(false));

        // dialing is canceled
        mTestConnectionService.onLocalHangup(c);

        // startEmergencyCall has completed
        future.complete(NOT_DISCONNECTED);

        // verify that createEmergencyConnection is discarded
        verify(mEmergencyCallDomainSelectionConnection, times(0))
                .createEmergencyConnection(any(), any());
    }

    @Test
    public void testDomainSelectionNormalToEmergencyLocalHangupCreateEmergencyConnection()
            throws Exception {
        setupForCallTest();

        int preciseDisconnectCause = com.android.internal.telephony.CallFailCause.ERROR_UNSPECIFIED;
        int disconnectCause = android.telephony.DisconnectCause.ERROR_UNSPECIFIED;
        int eccCategory = EMERGENCY_SERVICE_CATEGORY_POLICE;
        int selectedDomain = DOMAIN_CS;

        setupForDialForDomainSelection(mPhone0, selectedDomain, true);
        doReturn(mPhone0).when(mImsPhone).getDefaultPhone();

        TestTelephonyConnection c = setupForReDialForDomainSelection(
                mImsPhone, selectedDomain, preciseDisconnectCause, disconnectCause, false);
        c.setEmergencyServiceCategory(eccCategory);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        c.setTelecomCallId(TELECOM_CALL_ID1);

        CompletableFuture<Integer> future = new CompletableFuture<>();
        doReturn(future).when(mEmergencyCallDomainSelectionConnection)
                .createEmergencyConnection(any(), any());

        ImsReasonInfo reasonInfo = new ImsReasonInfo(CODE_SIP_ALTERNATE_EMERGENCY_CALL, 0, null);
        assertTrue(mTestConnectionService.maybeReselectDomain(c,
                  preciseDisconnectCause, reasonInfo));

        verify(mEmergencyCallDomainSelectionConnection).createEmergencyConnection(any(), any());

        // dialing is canceled
        mTestConnectionService.onLocalHangup(c);

        // domain selection has completed
        future.complete(selectedDomain);

        // verify that dialing is discarded
        verify(mPhone0, times(0)).dial(anyString(), any(), any());
    }

    @Test
    public void testDomainSelectionWithMmiCode() {
        //UT domain selection should not be handled by new domain selector.
        doNothing().when(mContext).startActivity(any());
        setupForCallTest();
        setupForDialForDomainSelection(mPhone0, 0, false);
        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1, "*%2321%23", TELECOM_CALL_ID1));

        verifyZeroInteractions(mNormalCallDomainSelectionConnection);
    }

    @Test
    public void testNormalCallPsDomainSelection() throws Exception {
        setupForCallTest();
        int selectedDomain = DOMAIN_PS;
        setupForDialForDomainSelection(mPhone0, selectedDomain, false);

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1, "1234", TELECOM_CALL_ID1));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(false));
        verify(mNormalCallDomainSelectionConnection).createNormalConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(
                selectedDomain, dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    @Test
    public void testNormalCallCsDomainSelection() throws Exception {
        setupForCallTest();
        int selectedDomain = DOMAIN_CS;
        setupForDialForDomainSelection(mPhone0, selectedDomain, false);

        mTestConnectionService.onCreateOutgoingConnection(PHONE_ACCOUNT_HANDLE_1,
                createConnectionRequest(PHONE_ACCOUNT_HANDLE_1, "1234", TELECOM_CALL_ID1));

        verify(mDomainSelectionResolver)
                .getDomainSelectionConnection(eq(mPhone0), eq(SELECTOR_TYPE_CALLING), eq(false));
        verify(mNormalCallDomainSelectionConnection).createNormalConnection(any(), any());

        ArgumentCaptor<DialArgs> argsCaptor = ArgumentCaptor.forClass(DialArgs.class);

        verify(mPhone0).dial(anyString(), argsCaptor.capture(), any());
        DialArgs dialArgs = argsCaptor.getValue();
        assertNotNull("DialArgs param is null", dialArgs);
        assertNotNull("intentExtras is null", dialArgs.intentExtras);
        assertTrue(dialArgs.intentExtras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN));
        assertEquals(
                selectedDomain, dialArgs.intentExtras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN, -1));
    }

    private void setupForDialForDomainSelection(Phone mockPhone, int domain, boolean isEmergency) {
        if (isEmergency) {
            doReturn(mEmergencyCallDomainSelectionConnection).when(mDomainSelectionResolver)
                    .getDomainSelectionConnection(any(), anyInt(), eq(true));
            doReturn(CompletableFuture.completedFuture(domain))
                    .when(mEmergencyCallDomainSelectionConnection)
                    .createEmergencyConnection(any(), any());
            doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(anyString());
        } else {
            doReturn(mNormalCallDomainSelectionConnection).when(mDomainSelectionResolver)
                    .getDomainSelectionConnection(any(), eq(SELECTOR_TYPE_CALLING), eq(false));
            doReturn(CompletableFuture.completedFuture(domain))
                    .when(mNormalCallDomainSelectionConnection)
                    .createNormalConnection(any(), any());
            doReturn(false).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(anyString());
        }

        doReturn(true).when(mDomainSelectionResolver).isDomainSelectionSupported();
        doReturn(mImsPhone).when(mockPhone).getImsPhone();
    }

    private TestTelephonyConnection setupForReDialForDomainSelection(
            Phone mockPhone, int domain, int preciseDisconnectCause,
            int disconnectCause, boolean fromEmergency) throws Exception {
        try {
            if (fromEmergency) {
                doReturn(CompletableFuture.completedFuture(domain))
                        .when(mEmergencyCallDomainSelectionConnection)
                        .reselectDomain(any());
                replaceInstance(TelephonyConnectionService.class,
                        "mEmergencyCallDomainSelectionConnection",
                        mTestConnectionService, mEmergencyCallDomainSelectionConnection);
                replaceInstance(TelephonyConnectionService.class, "mEmergencyCallId",
                        mTestConnectionService, TELECOM_CALL_ID1);
            } else {
                doReturn(CompletableFuture.completedFuture(domain))
                        .when(mNormalCallDomainSelectionConnection).reselectDomain(any());
                replaceInstance(TelephonyConnectionService.class, "mDomainSelectionConnection",
                        mTestConnectionService, mNormalCallDomainSelectionConnection);
            }
        } catch (Exception e) {
            // This shouldn't happen
            fail();
        }

        doReturn(true).when(mDomainSelectionResolver).isDomainSelectionSupported();

        TestTelephonyConnection c = new TestTelephonyConnection();
        c.setTelecomCallId(TELECOM_CALL_ID1);
        c.setMockPhone(mockPhone);
        c.setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED);

        Connection oc = c.getOriginalConnection();
        doReturn(disconnectCause).when(oc).getDisconnectCause();
        doReturn(preciseDisconnectCause).when(oc).getPreciseDisconnectCause();

        return c;
    }

    private SimpleTelephonyConnection createTestConnection(PhoneAccountHandle handle,
            int properties, boolean isEmergency) {
        SimpleTelephonyConnection connection = new SimpleTelephonyConnection();
        connection.setShouldTreatAsEmergencyCall(isEmergency);
        connection.setConnectionProperties(properties);
        connection.setPhoneAccountHandle(handle);
        return connection;
    }

    /**
     * Setup the mess of mocks for {@link #testSecondCallSameSubWontDisconnect()} and
     * {@link #testIncomingDoesntRequestDisconnect()}.
     */
    private void setupForCallTest() {
        // Setup a bunch of stuff.  Blech.
        mTestConnectionService.setReadyForTest();
        mPhone0 = makeTestPhone(0 /*phoneId*/, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        when(mCall.getState()).thenReturn(Call.State.INCOMING);
        when(mCall.getPhone()).thenReturn(mPhone0);
        when(mPhone0.getRingingCall()).thenReturn(mCall);
        mPhone1 = makeTestPhone(1 /*phoneId*/, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        when(mCall2.getPhone()).thenReturn(mPhone1);
        List<Phone> phones = new ArrayList<>(2);
        doReturn(true).when(mPhone0).isRadioOn();
        doReturn(true).when(mPhone1).isRadioOn();
        doReturn(GSM_PHONE).when(mPhone0).getPhoneType();
        doReturn(GSM_PHONE).when(mPhone1).getPhoneType();
        phones.add(mPhone0);
        phones.add(mPhone1);
        setPhones(phones);
        when(mPhoneUtilsProxy.getSubIdForPhoneAccountHandle(eq(PHONE_ACCOUNT_HANDLE_1)))
                .thenReturn(0);
        when(mSubscriptionManagerProxy.getPhoneId(0)).thenReturn(0);
        when(mPhoneFactoryProxy.getPhone(eq(0))).thenReturn(mPhone0);
        when(mPhoneUtilsProxy.getSubIdForPhoneAccountHandle(eq(PHONE_ACCOUNT_HANDLE_2)))
                .thenReturn(1);
        when(mSubscriptionManagerProxy.getPhoneId(1)).thenReturn(1);
        when(mPhoneFactoryProxy.getPhone(eq(1))).thenReturn(mPhone1);
        setupDeviceConfig(mPhone0, mPhone1, 1);

        when(mInternalConnection.getCall()).thenReturn(mCall);
        when(mInternalConnection.getState()).thenReturn(Call.State.ACTIVE);
        when(mInternalConnection2.getCall()).thenReturn(mCall2);
        when(mInternalConnection2.getState()).thenReturn(Call.State.WAITING);
    }

    /**
     * Set up a mock MSIM device with TEST_ADDRESS set as an emergency number.
     * @param isRoaming whether it is roaming
     * @param setOperatorName whether operator name needs to set
     * @param operatorNameLongName the operator long name if needs to set
     * @param operatorNameShortName the operator short name if needs to set
     * @param operatorNameNumeric the operator numeric name if needs to set
     * @return the Phone associated with slot 0.
     */
    private Phone setupConnectionServiceForDelayDial(boolean isRoaming, boolean setOperatorName,
            String operatorNameLongName, String operatorNameShortName,
                    String operatorNameNumeric) {
        ConnectionRequest connectionRequest = new ConnectionRequest.Builder()
                .setAccountHandle(PHONE_ACCOUNT_HANDLE_1)
                .setAddress(TEST_ADDRESS)
                .build();
        Phone testPhone0 = makeTestPhone(0 /*phoneId*/, ServiceState.STATE_IN_SERVICE,
                false /*isEmergencyOnly*/);
        Phone testPhone1 = makeTestPhone(1 /*phoneId*/, ServiceState.STATE_OUT_OF_SERVICE,
                false /*isEmergencyOnly*/);
        List<Phone> phones = new ArrayList<>(2);
        doReturn(true).when(testPhone0).isRadioOn();
        doReturn(true).when(testPhone1).isRadioOn();
        phones.add(testPhone0);
        phones.add(testPhone1);
        setPhones(phones);
        setupHandleToPhoneMap(PHONE_ACCOUNT_HANDLE_1, testPhone0);
        setupDeviceConfig(testPhone0, testPhone1, 1);
        doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(
                TEST_ADDRESS.getSchemeSpecificPart());
        HashMap<Integer, List<EmergencyNumber>> emergencyNumbers = new HashMap<>(1);
        List<EmergencyNumber> numbers = new ArrayList<>();
        numbers.add(setupEmergencyNumber(TEST_ADDRESS));
        emergencyNumbers.put(0 /*subId*/, numbers);
        doReturn(emergencyNumbers).when(mTelephonyManagerProxy).getCurrentEmergencyNumberList();
        doReturn(2).when(mTelephonyManagerProxy).getPhoneCount();
        testPhone0.getServiceState().setRoaming(isRoaming);
        if (setOperatorName) {
            testPhone0.getServiceState().setOperatorName(operatorNameLongName,
                    operatorNameShortName, operatorNameNumeric);
        }
        mConnection = mTestConnectionService.onCreateOutgoingConnection(
                PHONE_ACCOUNT_HANDLE_1, connectionRequest);
        assertNotNull("test connection was not set up correctly.", mConnection);
        return testPhone0;
    }

    /**
     * Set up a mock MSIM device with TEST_ADDRESS set as an emergency number in airplane mode.
     * @return the Phone associated with slot 0.
     */
    private Phone setupConnectionServiceInApm() {
        ConnectionRequest connectionRequest = new ConnectionRequest.Builder()
                .setAccountHandle(PHONE_ACCOUNT_HANDLE_1)
                .setAddress(TEST_ADDRESS)
                .build();
        Phone testPhone0 = makeTestPhone(0 /*phoneId*/, ServiceState.STATE_POWER_OFF,
                false /*isEmergencyOnly*/);
        Phone testPhone1 = makeTestPhone(1 /*phoneId*/, ServiceState.STATE_POWER_OFF,
                false /*isEmergencyOnly*/);
        doReturn(GSM_PHONE).when(testPhone0).getPhoneType();
        doReturn(GSM_PHONE).when(testPhone1).getPhoneType();
        List<Phone> phones = new ArrayList<>(2);
        doReturn(false).when(testPhone0).isRadioOn();
        doReturn(false).when(testPhone1).isRadioOn();
        phones.add(testPhone0);
        phones.add(testPhone1);
        setPhones(phones);
        setupHandleToPhoneMap(PHONE_ACCOUNT_HANDLE_1, testPhone0);
        setupDeviceConfig(testPhone0, testPhone1, 0);
        doReturn(true).when(mTelephonyManagerProxy).isCurrentEmergencyNumber(
                TEST_ADDRESS.getSchemeSpecificPart());
        HashMap<Integer, List<EmergencyNumber>> emergencyNumbers = new HashMap<>(1);
        List<EmergencyNumber> numbers = new ArrayList<>();
        numbers.add(setupEmergencyNumber(TEST_ADDRESS));
        emergencyNumbers.put(0 /*subId*/, numbers);
        doReturn(emergencyNumbers).when(mTelephonyManagerProxy).getCurrentEmergencyNumberList();
        doReturn(2).when(mTelephonyManagerProxy).getPhoneCount();

        mConnection = mTestConnectionService.onCreateOutgoingConnection(
                PHONE_ACCOUNT_HANDLE_1, connectionRequest);
        assertNotNull("test connection was not set up correctly.", mConnection);

        return testPhone0;
    }

    private EmergencyNumber setupEmergencyNumber(Uri address) {
        return new EmergencyNumber(address.getSchemeSpecificPart(), "", "",
        EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
        Collections.emptyList(),
        EmergencyNumber.EMERGENCY_NUMBER_SOURCE_SIM,
        EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY);
    }

    private void setupHandleToPhoneMap(PhoneAccountHandle handle, Phone phone) {
        // use subId 0
        when(mPhoneUtilsProxy.getSubIdForPhoneAccountHandle(eq(handle))).thenReturn(0);
        when(mSubscriptionManagerProxy.getPhoneId(eq(0))).thenReturn(0);
        when(mPhoneFactoryProxy.getPhone(eq(0))).thenReturn(phone);
    }

    private AsyncResult getSuppServiceNotification(int notificationType, int code) {
        SuppServiceNotification notification = new SuppServiceNotification();
        notification.notificationType = notificationType;
        notification.code = code;
        return new AsyncResult(null, notification, null);
    }

    private Phone makeTestPhone(int phoneId, int serviceState, boolean isEmergencyOnly) {
        Phone phone = mock(Phone.class);
        ServiceState testServiceState = new ServiceState();
        testServiceState.setState(serviceState);
        testServiceState.setEmergencyOnly(isEmergencyOnly);
        when(phone.getContext()).thenReturn(mContext);
        when(phone.getServiceState()).thenReturn(testServiceState);
        when(phone.getPhoneId()).thenReturn(phoneId);
        when(phone.getDefaultPhone()).thenReturn(phone);
        when(phone.getEmergencyNumberTracker()).thenReturn(mEmergencyNumberTracker);
        when(phone.getServiceStateTracker()).thenReturn(mSST);
        doNothing().when(phone).registerForPreciseCallStateChanged(any(Handler.class), anyInt(),
                any(Object.class));
        when(mEmergencyNumberTracker.getEmergencyNumber(anyString())).thenReturn(null);
        return phone;
    }

    // Setup 2 SIM device
    private void setupDeviceConfig(Phone slot0Phone, Phone slot1Phone, int defaultVoicePhoneId) {
        when(mTelephonyManagerProxy.getPhoneCount()).thenReturn(2);
        when(mTelephonyManagerProxy.isConcurrentCallsPossible()).thenReturn(false);
        when(mSubscriptionManagerProxy.getDefaultVoicePhoneId()).thenReturn(defaultVoicePhoneId);
        when(mPhoneFactoryProxy.getPhone(eq(SLOT_0_PHONE_ID))).thenReturn(slot0Phone);
        when(mPhoneFactoryProxy.getPhone(eq(SLOT_1_PHONE_ID))).thenReturn(slot1Phone);
    }

    private void setDefaultDataPhoneId(int defaultDataPhoneId) {
        when(mSubscriptionManagerProxy.getDefaultDataPhoneId()).thenReturn(defaultDataPhoneId);
    }

    private void setPhoneRadioAccessFamily(Phone phone, int radioAccessFamily) {
        when(phone.getRadioAccessFamily()).thenReturn(radioAccessFamily);
    }

    private void setEmergencySmsMode(Phone phone, boolean isInEmergencySmsMode) {
        when(phone.isInEmergencySmsMode()).thenReturn(isInEmergencySmsMode);
    }

    private void setPhoneSlotState(int slotId, int slotState) {
        when(mSubscriptionManagerProxy.getSimStateForSlotIdx(slotId)).thenReturn(slotState);
    }

    private void setDefaultPhone(Phone phone) {
        when(mPhoneFactoryProxy.getDefaultPhone()).thenReturn(phone);
    }

    private void setPhones(List<Phone> phones) {
        when(mPhoneFactoryProxy.getPhones()).thenReturn(phones.toArray(new Phone[phones.size()]));
        when(mPhoneFactoryProxy.getDefaultPhone()).thenReturn(phones.get(0));
    }

    private void setPhonesDialConnection(Phone phone, Connection c) {
        try {
            when(phone.dial(anyString(), any(), any())).thenReturn(c);
        } catch (CallStateException e) {
            // this shouldn't happen
            fail();
        }
    }

    private ConnectionRequest createConnectionRequest(
            PhoneAccountHandle accountHandle, String address, String callId) {
        return new ConnectionRequest.Builder()
                .setAccountHandle(accountHandle)
                .setAddress(Uri.parse("tel:" + address))
                .setExtras(new Bundle())
                .setTelecomCallId(callId)
                .build();
    }

    private Executor getExecutor() {
        return Runnable::run;
    }
}
