package com.github.conanchen.gedit.hello.grpc;

import com.github.conanchen.gedit.common.grpc.Status;
import com.github.conanchen.gedit.user.grpc.interceptor.LogInterceptor;
import com.google.gson.Gson;
import io.grpc.stub.StreamObserver;
import org.lognet.springboot.grpc.GRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;

@GRpcService(interceptors = {LogInterceptor.class},applyGlobalInterceptors = false)
public class HelloService extends HelloGrpc.HelloImplBase {
    private static final Logger log = LoggerFactory.getLogger(HelloService.class);
    private static final Gson gson = new Gson();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        final HelloReply.Builder replyBuilder = HelloReply.newBuilder()
                .setStatus(Status.newBuilder()
                        .setCode(Status.Code.OK)
                        .setDetails("Hello很高兴回复你，你的hello很温暖。")
                        .build())
                .setUuid("1")
                .setMessage(String.format("Hello %s@%s ", request.getName(), dateFormat.format(System.currentTimeMillis())))
                .setCreated(System.currentTimeMillis())
                .setLastUpdated(System.currentTimeMillis());
        HelloReply helloReply = replyBuilder.build();
        responseObserver.onNext(helloReply);
        log.info(String.format("HelloService.sayHello() %s:%s gson=%s", helloReply.getUuid(), helloReply.getMessage(), gson.toJson(helloReply)));
        responseObserver.onCompleted();
    }
}