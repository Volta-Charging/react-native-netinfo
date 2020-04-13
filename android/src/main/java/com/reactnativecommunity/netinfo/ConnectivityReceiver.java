/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.reactnativecommunity.netinfo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;

import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.core.net.ConnectivityManagerCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.reactnativecommunity.netinfo.types.CellularGeneration;
import com.reactnativecommunity.netinfo.types.ConnectionType;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

abstract class ConnectivityReceiver {
    private static final String TAG = "ConnectivityReceiver";
    private final ConnectivityManager mConnectivityManager;
    private final WifiManager mWifiManager;
    private final TelephonyManager mTelephonyManager;
    private final ReactApplicationContext mReactContext;

    @Nonnull private ConnectionType mConnectionType = ConnectionType.UNKNOWN;
    @Nullable private CellularGeneration mCellularGeneration = null;
    private boolean mIsInternetReachable = false;
    private Boolean mIsInternetReachableOverride;

    ConnectivityReceiver(ReactApplicationContext reactContext) {
        mReactContext = reactContext;
        mConnectivityManager =
                (ConnectivityManager) reactContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager =
                (WifiManager)
                        reactContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mTelephonyManager =
                (TelephonyManager) reactContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    abstract void register();

    abstract void unregister();

    public void getCurrentState(@Nullable final String requestedInterface, final Promise promise) {
        promise.resolve(createConnectivityEventMap(requestedInterface));
    }

    public void setIsInternetReachableOverride(boolean isInternetReachableOverride) {
        this.mIsInternetReachableOverride = isInternetReachableOverride;
        updateConnectivity(mConnectionType, mCellularGeneration, mIsInternetReachable);
    }

    public void clearIsInternetReachableOverride() {
        this.mIsInternetReachableOverride = null;
    }

    ReactApplicationContext getReactContext() {
        return mReactContext;
    }

    ConnectivityManager getConnectivityManager() {
        return mConnectivityManager;
    }

    void updateConnectivity(
            @Nonnull ConnectionType connectionType,
            @Nullable CellularGeneration cellularGeneration,
            boolean isInternetReachableRaw) {
        boolean isInternetReachable = mIsInternetReachableOverride == null
                ? isInternetReachableRaw
                : mIsInternetReachableOverride;

        // It is possible to get multiple broadcasts for the same connectivity change, so we only
        // update and send an event when the connectivity has indeed changed.
        boolean connectionTypeChanged = connectionType != mConnectionType;
        boolean cellularGenerationChanged = cellularGeneration != mCellularGeneration;
        boolean isInternetReachableChanged = isInternetReachable != mIsInternetReachable;

        if (connectionTypeChanged || cellularGenerationChanged || isInternetReachableChanged) {
            mConnectionType = connectionType;
            mCellularGeneration = cellularGeneration;
            mIsInternetReachable = isInternetReachable;
            sendConnectivityChangedEvent();
        }
    }

    private void sendConnectivityChangedEvent() {
        getReactContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("netInfo.networkStatusDidChange", createConnectivityEventMap(null));
    }

    private WritableMap createConnectivityEventMap(@Nullable final String requestedInterface) {
        WritableMap event = Arguments.createMap();

        // Add if WiFi is ON or OFF
        boolean isEnabled = mWifiManager.isWifiEnabled();
        event.putBoolean("isWifiEnabled", isEnabled);

        // Add the connection type information
        event.putString("type", requestedInterface != null ? requestedInterface : mConnectionType.label);

        // Add the connection state information
        boolean isConnected =
                !mConnectionType.equals(ConnectionType.NONE)
                        && !mConnectionType.equals(ConnectionType.UNKNOWN);
        event.putBoolean("isConnected", isConnected);

        // Add the internet reachable information
        event.putBoolean(
                "isInternetReachable",
                mIsInternetReachable && (requestedInterface == null || requestedInterface.equals(mConnectionType.label)));

        // Add the details, if there are any
        String detailsInterface = requestedInterface != null ? requestedInterface : mConnectionType.label;
        WritableMap details = createDetailsMap(detailsInterface);
        if (isConnected) {
            boolean isConnectionExpensive =
                    ConnectivityManagerCompat.isActiveNetworkMetered(getConnectivityManager());
            details.putBoolean("isConnectionExpensive", isConnectionExpensive);
        }
        event.putMap("details", details);

        return event;
    }

    private WritableMap createDetailsMap(@Nonnull String detailsInterface) {
        WritableMap details = Arguments.createMap();
        switch (detailsInterface) {
            case "cellular":
                // Add the cellular generation, if we have one
                if (mCellularGeneration != null) {
                    details.putString("cellularGeneration", mCellularGeneration.label);
                }

                // Add the network operator name, if there is one
                String carrier = mTelephonyManager.getNetworkOperatorName();
                if (carrier != null) {
                    details.putString("carrier", carrier);
                }

                // Add the signal strength
                if (ContextCompat.checkSelfPermission(getReactContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        // Get all the information of the cells of the device
                        for (CellInfo cellInfo : mTelephonyManager.getAllCellInfo()) {
                            // Check if the cell is registered
                            if (cellInfo != null && cellInfo.isRegistered()) {
                                if (cellInfo instanceof CellInfoGsm) {
                                    CellSignalStrengthGsm cellSignalStrengthGsm = ((CellInfoGsm) cellInfo).getCellSignalStrength();
                                    // Signal strength in dBm
                                    details.putInt("signalStrengthIndBm", cellSignalStrengthGsm.getDbm());
                                    // Signal strength in levels
                                    details.putInt("signalStrengthInLevel", cellSignalStrengthGsm.getLevel());
                                }
                                if (cellInfo instanceof CellInfoLte) {
                                    CellSignalStrengthLte cellSignalStrengthLte = ((CellInfoLte) cellInfo).getCellSignalStrength();
                                    // Signal strength in dBm
                                    details.putInt("signalStrengthIndBm", cellSignalStrengthLte.getDbm());
                                    // Signal strength in levels
                                    details.putInt("signalStrengthInLevel", cellSignalStrengthLte.getLevel());
                                }
                                if (cellInfo instanceof CellInfoCdma) {
                                    CellSignalStrengthCdma cellSignalStrengthCdma = ((CellInfoCdma) cellInfo).getCellSignalStrength();
                                    // Signal strength in dBm
                                    details.putInt("signalStrengthIndBm", cellSignalStrengthCdma.getDbm());
                                    // Signal strength in levels
                                    details.putInt("signalStrengthInLevel", cellSignalStrengthCdma.getLevel());
                                }
                                if (cellInfo instanceof CellInfoWcdma) {
                                    CellSignalStrengthWcdma cellSignalStrengthWcdma = ((CellInfoWcdma) cellInfo).getCellSignalStrength();
                                    // Signal strength in dBm
                                    details.putInt("signalStrengthIndBm", cellSignalStrengthWcdma.getDbm());
                                    // Signal strength in levels
                                    details.putInt("signalStrengthInLevel", cellSignalStrengthWcdma.getLevel());
                                }
                                break;
                            }
                        }
                    } catch (Exception exception) {
                        Log.e(TAG, "Unable to obtain cell signal information", exception);
                    }
                }
                break;
            case "wifi":
                if (ContextCompat.checkSelfPermission(getReactContext(),
                        Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                    WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                    if (wifiInfo != null) {
                        // Get the SSID
                        try {
                            String initialSSID = wifiInfo.getSSID();
                            if (initialSSID != null && !initialSSID.contains("<unknown ssid>")) {
                                // Strip the quotes, if any
                                String ssid = initialSSID.replace("\"", "");
                                details.putString("ssid", ssid);
                            }
                        } catch (Exception e) {
                            // Ignore errors
                        }

                        // Get/parse the wifi signal strength
                        try {
                            int signalStrength =
                                    WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 100);
                            details.putInt("strength", signalStrength);
                        } catch (Exception e) {
                            // Ignore errors
                        }

                        // Get the IP address
                        try {
                            byte[] ipAddressByteArray =
                                    BigInteger.valueOf(wifiInfo.getIpAddress()).toByteArray();
                            NetInfoUtils.reverseByteArray(ipAddressByteArray);
                            InetAddress inetAddress = InetAddress.getByAddress(ipAddressByteArray);
                            String ipAddress = inetAddress.getHostAddress();
                            details.putString("ipAddress", ipAddress);
                        } catch (Exception e) {
                            // Ignore errors
                        }

                        // Get the subnet mask
                        try {
                            byte[] ipAddressByteArray =
                                    BigInteger.valueOf(wifiInfo.getIpAddress()).toByteArray();
                            NetInfoUtils.reverseByteArray(ipAddressByteArray);
                            InetAddress inetAddress = InetAddress.getByAddress(ipAddressByteArray);
                            NetworkInterface netAddress =
                                    NetworkInterface.getByInetAddress(inetAddress);
                            int mask =
                                    0xffffffff
                                            << (32
                                            - netAddress
                                            .getInterfaceAddresses()
                                            .get(1)
                                            .getNetworkPrefixLength());
                            String subnet =
                                    String.format(
                                            Locale.US,
                                            "%d.%d.%d.%d",
                                            (mask >> 24 & 0xff),
                                            (mask >> 16 & 0xff),
                                            (mask >> 8 & 0xff),
                                            (mask & 0xff));
                            details.putString("subnet", subnet);
                        } catch (Exception e) {
                            // Ignore errors
                        }
                    }
                }
                break;
        }
        return details;
    }
}
