package com.devicehive.service;

import com.devicehive.dao.DeviceDao;
import com.devicehive.model.DeviceNotification;
import com.devicehive.model.SpecialNotifications;
import com.devicehive.model.eventbus.events.NotificationEvent;
import com.devicehive.model.rpc.*;
import com.devicehive.model.wrappers.DeviceNotificationWrapper;
import com.devicehive.service.helpers.ResponseConsumer;
import com.devicehive.service.time.TimestampService;
import com.devicehive.shim.api.Request;
import com.devicehive.shim.api.Response;
import com.devicehive.shim.api.client.RpcClient;
import com.devicehive.util.ServerResponsesFactory;
import com.devicehive.vo.DeviceVO;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DeviceNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceNotificationService.class);

    private DeviceEquipmentService deviceEquipmentService;
    private TimestampService timestampService;
    private DeviceDao deviceDao;
    private RpcClient rpcClient;

    @Autowired
    public DeviceNotificationService(DeviceEquipmentService deviceEquipmentService,
                                     TimestampService timestampService,
                                     DeviceDao deviceDao,
                                     RpcClient rpcClient) {
        this.deviceEquipmentService = deviceEquipmentService;
        this.timestampService = timestampService;
        this.deviceDao = deviceDao;
        this.rpcClient = rpcClient;
    }

    public CompletableFuture<Optional<DeviceNotification>> findOne(Long id, String guid) {
        NotificationSearchRequest searchRequest = new NotificationSearchRequest();
        searchRequest.setId(id);
        searchRequest.setGuid(guid);

        CompletableFuture<Response> future = new CompletableFuture<>();
        rpcClient.call(Request.newBuilder()
                .withBody(searchRequest)
                .withPartitionKey(searchRequest.getGuid())
                .build(), new ResponseConsumer(future));
        return future.thenApply(r -> ((NotificationSearchResponse) r.getBody()).getNotifications().stream().findFirst());
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<DeviceNotification>> find(Set<String> guids, Set<String> names,
                                                            Date timestampSt, Date timestampEnd) {
        List<CompletableFuture<Response>> futures = guids.stream()
                .map(guid -> {
                    NotificationSearchRequest searchRequest = new NotificationSearchRequest();
                    searchRequest.setGuid(guid);
                    searchRequest.setNames(names);
                    searchRequest.setTimestampStart(timestampSt);
                    searchRequest.setTimestampEnd(timestampEnd);
                    return searchRequest;
                })
                .map(searchRequest -> {
                    CompletableFuture<Response> future = new CompletableFuture<>();
                    rpcClient.call(Request.newBuilder()
                            .withBody(searchRequest)
                            .withPartitionKey(searchRequest.getGuid())
                            .build(), new ResponseConsumer(future));
                    return future;
                })
                .collect(Collectors.toList());

        // List<CompletableFuture<Response>> => CompletableFuture<List<DeviceNotification>>
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)                                                    // List<CompletableFuture<Response>> => CompletableFuture<List<Response>>
                        .map(r -> r.getBody().cast(NotificationSearchResponse.class).getNotifications()) // CompletableFuture<List<Response>> => CompletableFuture<List<List<DeviceNotification>>>
                        .flatMap(Collection::stream)                                                     // CompletableFuture<List<List<DeviceNotification>>> => CompletableFuture<List<DeviceNotification>>
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<DeviceNotification> insert(final DeviceNotification notification,
                                                        final DeviceVO device) {
        List<CompletableFuture<Response>> futures = processDeviceNotification(notification, device).stream()
                .map(n -> {
                    CompletableFuture<Response> future = new CompletableFuture<>();
                    rpcClient.call(Request.newBuilder()
                            .withBody(new NotificationInsertRequest(n))
                            .withPartitionKey(device.getGuid())
                            .build(), new ResponseConsumer(future));
                    return future;
                })
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(x -> futures.stream()
                        .map(CompletableFuture::join)
                        .map(r -> r.getBody().cast(NotificationInsertResponse.class).getDeviceNotification())
                        .filter(n -> !SpecialNotifications.DEVICE_UPDATE.equals(n.getNotification())) // we are not going to return DEVICE_UPDATE notification
                        .collect(Collectors.toList()).get(0)); // after filter we should get only one notification
    }

    public void insert(final DeviceNotification notification, final String deviceGuid) {
        notification.setTimestamp(timestampService.getDate());
        notification.setId(Math.abs(new Random().nextInt()));
        notification.setDeviceGuid(deviceGuid);
        rpcClient.push(Request.newBuilder()
                .withBody(new NotificationInsertRequest(notification))
                .withPartitionKey(deviceGuid)
                .build());
    }

    public Pair<String, CompletableFuture<List<DeviceNotification>>> sendSubscribeRequest(
            final Set<String> devices,
            final Set<String> names,
            final Date timestamp,
            final BiConsumer<DeviceNotification, String> callback) {

        final String subscriptionId = UUID.randomUUID().toString();
        Set<NotificationSubscribeRequest> subscribeRequests = devices.stream()
                .map(device -> new NotificationSubscribeRequest(subscriptionId, device, names, timestamp))
                .collect(Collectors.toSet());
        Collection<CompletableFuture<Collection<DeviceNotification>>> futures = new ArrayList<>();
        for (NotificationSubscribeRequest sr : subscribeRequests) {
            CompletableFuture<Collection<DeviceNotification>> future = new CompletableFuture<>();
            Consumer<Response> responseConsumer = response -> {
                String resAction = response.getBody().getAction();
                if (resAction.equals(Action.NOTIFICATION_SUBSCRIBE_RESPONSE.name())) {
                    NotificationSubscribeResponse r = response.getBody().cast(NotificationSubscribeResponse.class);
                    future.complete(r.getNotifications());
                } else if (resAction.equals(Action.NOTIFICATION_EVENT.name())) {
                    NotificationEvent event = response.getBody().cast(NotificationEvent.class);
                    callback.accept(event.getNotification(), subscriptionId);
                } else {
                    logger.warn("Unknown action received from backend {}", resAction);
                }
            };
            futures.add(future);
            Request request = Request.newBuilder()
                    .withBody(sr)
                    .withPartitionKey(sr.getDevice())
                    .withSingleReply(false)
                    .build();
            rpcClient.call(request, responseConsumer);
        }

        CompletableFuture<List<DeviceNotification>> future = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList()));
        return Pair.of(subscriptionId, future);
    }

    public void submitNotificationUnsubscribe(String subId, Set<String> deviceGuids) {
        NotificationUnsubscribeRequest unsubscribeRequest = new NotificationUnsubscribeRequest(subId, deviceGuids);
        Request request = Request.newBuilder()
                .withBody(unsubscribeRequest)
                .build();
        rpcClient.push(request);
    }

    public DeviceNotification convertToMessage(DeviceNotificationWrapper notificationSubmit, DeviceVO device) {
        DeviceNotification message = new DeviceNotification();
        message.setId(Math.abs(new Random().nextInt()));
        message.setDeviceGuid(device.getGuid());
        message.setTimestamp(timestampService.getDate());
        message.setNotification(notificationSubmit.getNotification());
        message.setParameters(notificationSubmit.getParameters());
        return message;
    }

    private List<DeviceNotification> processDeviceNotification(DeviceNotification notificationMessage, DeviceVO device) {
        List<DeviceNotification> notificationsToCreate = new ArrayList<>();
        switch (notificationMessage.getNotification()) {
            case SpecialNotifications.EQUIPMENT:
                deviceEquipmentService.refreshDeviceEquipment(notificationMessage, device);
                break;
            case SpecialNotifications.DEVICE_STATUS:
                DeviceNotification deviceNotification = refreshDeviceStatusCase(notificationMessage, device);
                notificationsToCreate.add(deviceNotification);
                break;
            default:
                break;

        }
        notificationsToCreate.add(notificationMessage);
        return notificationsToCreate;

    }

    private DeviceNotification refreshDeviceStatusCase(DeviceNotification notificationMessage, DeviceVO device) {
        DeviceVO devicevo = deviceDao.findByUUID(device.getGuid());
        String status = ServerResponsesFactory.parseNotificationStatus(notificationMessage);
        devicevo.setStatus(status);
        return ServerResponsesFactory.createNotificationForDevice(devicevo, SpecialNotifications.DEVICE_UPDATE);
    }
}
