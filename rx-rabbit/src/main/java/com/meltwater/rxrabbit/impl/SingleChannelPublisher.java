package com.meltwater.rxrabbit.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.meltwater.rxrabbit.ChannelFactory;
import com.meltwater.rxrabbit.Exchange;
import com.meltwater.rxrabbit.Payload;
import com.meltwater.rxrabbit.PublishChannel;
import com.meltwater.rxrabbit.PublishEvent;
import com.meltwater.rxrabbit.RabbitPublisher;
import com.meltwater.rxrabbit.RoutingKey;
import com.meltwater.rxrabbit.PublishEventListener;
import com.meltwater.rxrabbit.util.Fibonacci;
import com.meltwater.rxrabbit.util.Logger;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConfirmListener;
import rx.Scheduler;
import rx.Single;
import rx.SingleSubscriber;
import rx.Subscription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

//TODO javadoc
public class SingleChannelPublisher implements RabbitPublisher {

    private static final Logger log = new Logger(SingleChannelPublisher.class);

    private final int maxRetries;
    private final boolean publisherConfirms;
    private final long closeTimeoutMillis;

    private final ChannelFactory channelFactory;
    private final PublishEventListener metricsReporter;

    private final Scheduler.Worker ackWorker;
    private final Scheduler.Worker publishWorker;
    private final Scheduler.Worker cacheCleanupWorker;

    private final Cache<Long, UnconfirmedMessage> tagToMessage;
    private final AtomicLong largestSeqSeen = new AtomicLong(0);
    private final AtomicLong seqOffset = new AtomicLong(0);


    private PublishChannel channel = null;

    public SingleChannelPublisher(ChannelFactory channelFactory,
                                  boolean publisherConfirms,
                                  int maxRetries,
                                  Scheduler scheduler,
                                  PublishEventListener metricsReporter,
                                  long confirmsTimeoutSec,
                                  long closeTimeoutMillis,
                                  long cacheCleanupTriggerSecs) {
        this.channelFactory = channelFactory;
        this.publisherConfirms = publisherConfirms;
        this.maxRetries = maxRetries;
        this.closeTimeoutMillis = closeTimeoutMillis;
        this.metricsReporter = metricsReporter;

        this.publishWorker = scheduler.createWorker();
        publishWorker.schedule(() -> Thread.currentThread().setName("rabbit-send-thread")); //TODO thread name
        this.ackWorker = scheduler.createWorker();
        ackWorker.schedule(() -> Thread.currentThread().setName("rabbit-confirm-thread")); //TODO thread name
        this.cacheCleanupWorker = scheduler.createWorker();
        cacheCleanupWorker.schedule(() -> Thread.currentThread().setName("cache-cleanup")); //TODO thread name

        if (publisherConfirms) {
            this.tagToMessage = CacheBuilder.<Long, UnconfirmedMessage>newBuilder()
                    .expireAfterAccess(confirmsTimeoutSec, TimeUnit.SECONDS)
                    .removalListener(this::handleCacheRemove)
                    .build();
            cacheCleanupWorker.schedulePeriodically(tagToMessage::cleanUp, cacheCleanupTriggerSecs, cacheCleanupTriggerSecs, TimeUnit.SECONDS);
        }else{
            this.tagToMessage = null;
        }
    }


    @Override
    public void close() throws IOException {
        //TODO add logging ??
        //TODO what to do with the non returned Singles???
        try {
            //TODO read the boolean returned from waitFor..
            if (closeTimeoutMillis >0){
                boolean allConfirmed = channel.waitForConfirms(closeTimeoutMillis);
            }else {
                channel.waitForConfirms();
            }
        } catch (Exception e) {
            log.warnWithParams("Error when waiting for confirms during publisher close.");
            //TODO send onError to all subscribers??
        }finally {
            if(channel != null){
                channel.close();
            }
        }
        publishWorker.unsubscribe();
        ackWorker.unsubscribe();
        cacheCleanupWorker.unsubscribe();
    }


    @Override
    public Single<Void> call(Exchange exchange, RoutingKey routingKey, AMQP.BasicProperties basicProperties, Payload payload) {
        return Single.<Void>create(subscriber -> schedulePublish(exchange, routingKey, basicProperties, payload, 1, 0, subscriber));
    }

    public Subscription schedulePublish(Exchange exchange,
                                        RoutingKey routingKey,
                                        AMQP.BasicProperties props,
                                        Payload payload,
                                        int attempt,
                                        int delaySec,
                                        SingleSubscriber<? super Void> subscriber) {
        long schedulingStart = System.currentTimeMillis();
        return publishWorker.schedule(() -> basicPublish(exchange, routingKey, props, payload, attempt, subscriber, schedulingStart), delaySec, TimeUnit.SECONDS);
    }

    private synchronized void basicPublish(Exchange exchange, RoutingKey routingKey, AMQP.BasicProperties props, Payload payload, int attempt, SingleSubscriber<? super Void> subscriber, long schedulingStart) {
        final long publishStart = System.currentTimeMillis();
        UnconfirmedMessage message = new UnconfirmedMessage(this, subscriber,
                exchange,
                routingKey,
                props,
                payload,
                schedulingStart,
                publishStart,
                attempt);
        long seqNo;
        try {
            seqNo = getChannel().getNextPublishSeqNo();
        } catch (Exception error) {
            handleChannelException(exchange,
                    routingKey,
                    props,
                    message,
                    error,
                    "Error when creating channel. The connection and the channel is now considered broken.");
            return;
        }
        final long internalSeqNr = seqNo + seqOffset.get();
        if (largestSeqSeen.get() < internalSeqNr) {
            largestSeqSeen.set(internalSeqNr);
        }
        try {
            beforePublish(message);
            getChannel().basicPublish(exchange.name, routingKey.value, props, payload.data);
            message.setPublishCompletedAtTimestamp(System.currentTimeMillis());
            message.setPublished(true);
            afterPublish(message);
            if (publisherConfirms) {
                tagToMessage.put(internalSeqNr, message);
            }else{
                message.ack();
            }
        } catch (Exception error) {
            handleChannelException(exchange,
                    routingKey,
                    props,
                    message,
                    error,
                    "Error when calling basicPublish. The connection and the channel is now considered broken.");
        }
    }

    private synchronized PublishChannel getChannel() throws IOException, TimeoutException {
        if (channel==null){
            for (int i = 0; i < maxRetries || maxRetries<=0; i++) {
                try {
                    Thread.sleep(Fibonacci.getDelayMillis(i));
                    log.infoWithParams("Creating publish channel.");
                    this.channel = channelFactory.createPublishChannel();
                    //TODO do not add confirm listener if not publish confirms!!
                    channel.addConfirmListener(new InternalConfirmListener(ackWorker,this));
                    break;
                } catch (Exception ignored) {
                    log.warnWithParams("Failed to create connection. will try again.",
                            "attempt", i,
                            "maxAttempts", maxRetries,
                            "secsUntilNextAttempt", Fibonacci.getDelaySec(i));
                }
            }
        }
        if (channel==null){
            throw new TimeoutException("Failed to create channel after "+maxRetries+" attempts.");
        }
        return channel;
    }

    private synchronized void closeChannelWithError() {
        if (channel!=null) {
            channel.closeWithError();
            channel = null;
        }
        seqOffset.set(largestSeqSeen.get());
    }

    private synchronized Collection<Long> getAllPreviousTags(long deliveryTag, boolean multiple) {
        final long currOffset = seqOffset.get();
        long internalTag = deliveryTag + currOffset;
        Collection<Long> confirmedTags = new ArrayList<>();
        if (multiple) {
            confirmedTags = new ArrayList<>(new TreeMap<>(tagToMessage.asMap())
                    .tailMap(currOffset) //Since we don't want to ack old messages
                    .headMap(internalTag)
                    .keySet());
        }
        confirmedTags.add(internalTag);
        return confirmedTags;
    }

    private void handleChannelException(Exchange exchange, RoutingKey routingKey, AMQP.BasicProperties props, UnconfirmedMessage message, Exception e, String logMsg) {
        //TODO should we look at the error and do different things depending on the type??
        log.errorWithParams(logMsg, e,
                "exchange", exchange,
                "routingKey", routingKey,
                "basicProperties", props);
        closeChannelWithError();
        message.nack(e);
    }

    private void handleCacheRemove(RemovalNotification<Long, UnconfirmedMessage> notification) {
        if (notification.getCause().equals(RemovalCause.EXPIRED)) {
            UnconfirmedMessage message = notification.getValue();
            if (message != null) { //TODO how can this be null??
                ackWorker.schedule(() -> {
                    if (message.published) {
                        log.warnWithParams("Message did not receive publish-confirm in time", "messageId", message.props.getMessageId());
                    } //TODO send metric on the timeout event
                    message.nack(new TimeoutException("Message did not receive publish confirm in time"));
                });
            }
        }
    }

    private int getMaxRetries() {
        return maxRetries;
    }

    private void beforePublish(UnconfirmedMessage message){
        metricsReporter.beforePublish(getEvent(message));
    }

    private void afterPublish(UnconfirmedMessage message) {
        metricsReporter.afterPublish(getEvent(message));
    }

    private void afterFinalFail(UnconfirmedMessage message, Exception e) {
        metricsReporter.afterFinalFail(getEvent(message), e);
    }

    private void afterIntermediateFail(UnconfirmedMessage message, Exception e, int delaySec) {
        metricsReporter.afterIntermediateFail(getEvent(message), e, delaySec);
    }

    private void afterAck(UnconfirmedMessage message) {
        metricsReporter.afterConfirm(getEvent(message));
    }

    private PublishEvent getEvent(UnconfirmedMessage message) {
        return new PublishEvent(message.payload,
                message.exchange,
                message.routingKey,
                message.props,
                message.attempt,
                publisherConfirms,
                message.createdAtTimestamp,
                message.publishedAtTimestamp,
                message.publishCompletedAtTimestamp);
    }

    static class InternalConfirmListener implements ConfirmListener{

        final Scheduler.Worker ackWorker;
        final SingleChannelPublisher publisher;

        InternalConfirmListener(Scheduler.Worker ackWorker, SingleChannelPublisher publisher) {
            this.ackWorker = ackWorker;
            this.publisher = publisher;
        }

        @Override
        public void handleAck(long deliveryTag, boolean multiple) {
            ackWorker.schedule(() -> {
                for (Long k : publisher.getAllPreviousTags(deliveryTag, multiple)) {
                    log.traceWithParams("Handling confirm-ack for delivery tag",
                            "deliveryTag", deliveryTag,
                            "tag", k,
                            "multiple", multiple);
                    final UnconfirmedMessage remove = publisher.tagToMessage.getIfPresent(k);
                    if(remove != null){
                        publisher.tagToMessage.invalidate(k);
                        remove.ack();
                    }
                }
            });
        }
        @Override
        public void handleNack(long deliveryTag, boolean multiple) {
            ackWorker.schedule(() -> {
                for (Long k : publisher.getAllPreviousTags(deliveryTag, multiple)) {
                    log.traceWithParams("Handling confirm-nack for delivery tag",
                            "deliveryTag", deliveryTag,
                            "tag", k,
                            "multiple", multiple);
                    final UnconfirmedMessage remove = publisher.tagToMessage.getIfPresent(k);
                    if(remove != null){
                        publisher.tagToMessage.invalidate(k);
                        remove.nack(new IOException("Publisher sent nack on confirm return. deliveryTag=" + deliveryTag));
                    }
                }
            });
        }

    }

    static class UnconfirmedMessage {
        final SingleChannelPublisher publisher;
        final Payload payload;
        final RoutingKey routingKey;
        final Exchange exchange;
        final AMQP.BasicProperties props;
        final SingleSubscriber<? super Void> subscriber;
        final long createdAtTimestamp;
        final long publishedAtTimestamp;
        final int attempt;

        boolean published = false;
        long publishCompletedAtTimestamp;

        UnconfirmedMessage(SingleChannelPublisher publisher,
                           SingleSubscriber<? super Void> subscriber,
                           Exchange exchange,
                           RoutingKey routingKey,
                           AMQP.BasicProperties props,
                           Payload payload,
                           long createdAtTimestamp,
                           long publishedAtTimestamp,
                           int attempt) {
            this.publisher = publisher;
            this.exchange = exchange;
            this.payload = payload;
            this.subscriber = subscriber;
            this.routingKey = routingKey;
            this.props = props;
            this.createdAtTimestamp = createdAtTimestamp;
            this.publishedAtTimestamp = publishedAtTimestamp;
            this.attempt = attempt;
        }

        public void setPublishCompletedAtTimestamp(long time) {
            this.publishCompletedAtTimestamp = time;
        }

        public void setPublished(boolean published) {
            this.published = published;
        }

        public void ack() {
            publisher.afterAck(this);
            subscriber.onSuccess(null);
        }

        public void nack(Exception e) {
            double maxRetries = publisher.getMaxRetries();
            if (attempt < maxRetries || maxRetries <= 0) {
                int delaySec = Fibonacci.getDelaySec(attempt);
                publisher.afterIntermediateFail(this, e, delaySec);
                publisher.schedulePublish(exchange, routingKey, props, payload, attempt + 1, delaySec, subscriber);
            } else {
                publisher.afterFinalFail(this, e);
                subscriber.onError(e);
            }
        }

    }
}