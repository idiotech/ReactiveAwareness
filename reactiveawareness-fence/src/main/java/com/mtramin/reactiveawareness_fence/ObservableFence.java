/*
 * Copyright 2016 Marvin Ramin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mtramin.reactiveawareness_fence;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.google.android.gms.awareness.Awareness;
import com.google.android.gms.awareness.fence.AwarenessFence;
import com.google.android.gms.awareness.fence.FenceState;
import com.google.android.gms.awareness.fence.FenceUpdateRequest;
import com.google.android.gms.common.api.GoogleApiClient;
import com.mtramin.servant.ClientException;
import com.mtramin.servant.Servant;

import rx.Observable;
import rx.Subscriber;
import rx.subscriptions.Subscriptions;

/**
 * A Fence whose state can be observed.
 *
 * Make sure to unsubscribe from this fence when appropriate to not leak the fence and keep your
 * application alive in the background.
 *
 * When you unsubscribe from the resulting {@link rx.Subscription} will also automatically
 * unregister the fence.
 */
public class ObservableFence implements Observable.OnSubscribe<Boolean> {

    private static final String RECEIVER_ACTION = "ACTION_REACTIVE_AWARENESS";
    private static final int REQUEST_CODE = 42390857;
    private static final String OBSERVABLE_FENCE = "ObservableFence";

    private final Context context;
    private final GoogleApiClient googleApiClient;
    private final AwarenessFence fence;

    private ObservableFence(Context context, GoogleApiClient client, AwarenessFence fence) {
        this.context = context;
        this.googleApiClient = client;
        this.fence = fence;
    }

    /**
     * Creates an observable fence that will deliver status updates as an {@link Observable}.
     *
     * Unsubscribing from the resulting {@link rx.Subscription} will also unregister the fence.
     *
     * @param context context to use
     * @param fence the fence to register
     * @return Observable state updates to the fences state where {@code true} means that the fence
     * condition is valid
     */
    public static Observable<Boolean> create(Context context, AwarenessFence fence) {
        return Servant.observable(context.getApplicationContext(), Awareness.API)
                .flatMap(client -> Observable.create(new ObservableFence(context, client, fence)));
    }

    @Override
    public void call(Subscriber<? super Boolean> subscriber) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                FenceState state = FenceState.extract(intent);
                boolean result = state.getCurrentState() == FenceState.TRUE;
                subscriber.onNext(result);
            }
        };

        context.registerReceiver(receiver, new IntentFilter(RECEIVER_ACTION));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, new Intent(RECEIVER_ACTION), 0);

        FenceUpdateRequest fenceUpdateRequest = new FenceUpdateRequest.Builder()
                .addFence(OBSERVABLE_FENCE, fence, pendingIntent)
                .build();

        Awareness.FenceApi.updateFences(googleApiClient, fenceUpdateRequest)
                .setResultCallback(status -> {
                    if (!status.isSuccess()) {
                        subscriber.onError(new ClientException("Error adding observable fence. " + status.getStatusMessage()));
                    }
                    subscriber.onCompleted();
                });

        subscriber.add(Subscriptions.create(() -> {
            context.unregisterReceiver(receiver);
            unregisterFenceRequest(googleApiClient, subscriber);
        }));
    }

    private void unregisterFenceRequest(GoogleApiClient googleApiClient, Subscriber<? super Boolean> subscriber) {
        FenceUpdateRequest fenceUpdateRequest = new FenceUpdateRequest.Builder()
                .removeFence(OBSERVABLE_FENCE)
                .build();

        Awareness.FenceApi.updateFences(googleApiClient, fenceUpdateRequest)
                .setResultCallback(status -> {
                    if (!status.isSuccess()) {
                        subscriber.onError(new ClientException("Error removing observable fence. " + status.getStatusMessage()));
                    }

                    if (googleApiClient.isConnecting() || googleApiClient.isConnected()) {
                        googleApiClient.disconnect();
                    }
                    subscriber.onCompleted();
                });
    }


}
