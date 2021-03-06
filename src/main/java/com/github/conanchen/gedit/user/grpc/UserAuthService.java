package com.github.conanchen.gedit.user.grpc;

import com.github.conanchen.gedit.accounting.account.grpc.AccountResponse;
import com.github.conanchen.gedit.common.grpc.Status;
import com.github.conanchen.gedit.user.auth.grpc.*;
import com.github.conanchen.gedit.user.grpc.callback.GrpcApiCallback;
import com.github.conanchen.gedit.user.grpc.client.AccountingClient;
import com.github.conanchen.gedit.user.grpc.interceptor.AuthInterceptor;
import com.github.conanchen.gedit.user.grpc.interceptor.LogInterceptor;
import com.github.conanchen.gedit.user.model.Login;
import com.github.conanchen.gedit.user.model.User;
import com.github.conanchen.gedit.user.repository.LoginRepository;
import com.github.conanchen.gedit.user.repository.UserRepository;
import com.github.conanchen.gedit.user.service.CaptchaService;
import com.github.conanchen.gedit.user.thirdpart.sms.MsgSend;
import com.martiansoftware.validation.Hope;
import com.martiansoftware.validation.UncheckedValidationException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.CompressionCodecs;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
@Slf4j
@GRpcService(applyGlobalInterceptors = false, interceptors = {LogInterceptor.class})
public class UserAuthService extends UserAuthApiGrpc.UserAuthApiImplBase {
    @Value("${jjwt.expire.minutes:5}")
    private Long expiredInMinutes;
    @Value("${jjwt.sigin.key:shuai}")
    private String signinKey;
    @Value("${sms.active:false}")
    private Boolean smsActive;
    @Autowired
    private CaptchaService captchaService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MsgSend msgSend;
    @Autowired
    private LoginRepository loginRepository;
    @Autowired
    private AccountingClient accountingClient;

    @Override
    public void signinQQ(SigninQQRequest request, StreamObserver<SigninResponse> responseObserver) {
    }

    @Override
    public void signinWechat(SigninWechatRequest request, StreamObserver<SigninResponse> responseObserver) {

    }

    @Override
    public void signinWeibo(SigninWeiboRequest request, StreamObserver<SigninResponse> responseObserver) {
    }

    @Override
    public void signinWithPassword(SigninWithPasswordRequest request, StreamObserver<SigninResponse> responseObserver) {
        Status.Builder builder = Status.newBuilder();
        String accessToken = "";
        Date date = null;
        try {
            String mobile = Hope.that(request.getMobile()).named("mobile")
                    .isNotNullOrEmpty()
                    .matches("^(13|14|15|16|17|18|19)\\d{9}$")
                    .value();
            String password = Hope.that(request.getPassword()).named("password")
                    .isNotNullOrEmpty()
                    .value();
            User user = userRepository.findByMobile(mobile);
            if (user ==  null){
                builder.setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("账户不存在");
            }
            if (!user.getActive()) {
                builder.setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("账户被禁用");
            }
            if (DigestUtils.sha256Hex(password).equals(user.getPassword())) {
                date = expireDate();
                String compactJws = generate(user.getUuid(), new Date(), expireDate());
                accessToken = compactJws;
                builder.setCode(Status.Code.OK)
                        .setDetails("登录成功");
            } else {
                builder.setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("用户名或密码错误");
            }
        } catch (UncheckedValidationException e) {
            builder.setCode(Status.Code.INVALID_ARGUMENT)
                    .setDetails(e.getMessage());
        }
        responseObserver.onNext(SigninResponse.newBuilder()
                .setStatus(builder.build())
                .setAccessToken(accessToken)
                .setExpiresIn(date == null ? "" : String.valueOf(date.getTime()))
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void signinSmsStep1Question(SmsStep1QuestionRequest request, StreamObserver<SmsStep1QuestionResponse> responseObserver) {
        responseObserver.onNext(captchaService.listImgs());
        responseObserver.onCompleted();
    }

    @Override
    public void signinSmsStep2Answer(SmsStep2AnswerRequest request, StreamObserver<SmsStep2AnswerResponse> responseObserver) {
        Status.Builder builder = Status.newBuilder();
        try {
            String mobile = Hope.that(request.getMobile()).named("mobile")
                    .isNotNullOrEmpty()
                    .matches("^(13|14|15|16|17|18|19)\\d{9}$")
                    .value();
            User user = userRepository.findByMobile(mobile);
            if (user != null) {
                if (!user.getActive()) {
                    builder.setCode(Status.Code.FAILED_PRECONDITION)
                            .setDetails("用户已禁用");
                } else {
                    responseObserver.onNext(captchaService.verify(request));
                    responseObserver.onCompleted();
                    return;
                }
            } else {
                builder.setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("用户未注册,请返回注册");
            }
        } catch (UncheckedValidationException e) {
            builder.setCode(Status.Code.INVALID_ARGUMENT)
                    .setDetails(e.getMessage());
        } catch (StatusRuntimeException e){
            builder.setCode(Status.Code.forNumber(e.getStatus().getCode().value()))
                    .setDetails(e.getMessage());
        }
        responseObserver.onNext(SmsStep2AnswerResponse.newBuilder()
                .setStatus(builder.build())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void signinSmsStep3Signin(SmsStep3SigninRequest request, StreamObserver<SigninResponse> responseObserver) {
        SigninResponse.Builder builder = SigninResponse.newBuilder();
        try {
            String mobile = Hope.that(request.getMobile()).named("mobile")
                    .isNotNullOrEmpty()
                    .matches("^(13|14|15|16|17|18|19)\\d{9}$")
                    .value();
            User user = userRepository.findByMobile(mobile);
            if (user != null) {
                if (!user.getActive()) {
                    Status status = Status.newBuilder()
                            .setCode(Status.Code.FAILED_PRECONDITION)
                            .setDetails("用户已禁用")
                            .build();
                    builder.setStatus(status);
                } else {
                    if (smsActive) {
                        if (msgSend.verify(request.getMobile(), request.getSmscode())) {
                            signinSms(user, builder);
                        } else {
                            Status status = Status.newBuilder()
                                    .setCode(Status.Code.FAILED_PRECONDITION)
                                    .setDetails("验证失败，请重试")
                                    .build();
                            builder.setStatus(status);
                        }
                    } else {
                        signinSms(user, builder);
                    }

                }

            } else {
                Status status = Status.newBuilder()
                        .setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("用户未注册,请返回注册")
                        .build();
                builder.setStatus(status);
            }
        } catch (UncheckedValidationException e) {
            Status status = Status.newBuilder().setCode(Status.Code.INVALID_ARGUMENT)
                    .setDetails(e.getMessage())
                    .build();
            builder.setStatus(status);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void registerSmsStep1Question(SmsStep1QuestionRequest request, StreamObserver<SmsStep1QuestionResponse> responseObserver) {
        responseObserver.onNext(captchaService.listImgs());
        responseObserver.onCompleted();
    }

    @Override
    public void registerSmsStep2Answer(SmsStep2AnswerRequest request, StreamObserver<SmsStep2AnswerResponse> responseObserver) {
        Status status;
        try {
            captchaService.verify(request);
            status = Status.newBuilder()
                    .setCode(Status.Code.OK)
                    .setDetails("success")
                    .build();
        } catch (UncheckedValidationException e) {
            status = Status.newBuilder()
                    .setCode(Status.Code.INVALID_ARGUMENT)
                    .setDetails(e.getMessage())
                    .build();
        }
        responseObserver.onNext(SmsStep2AnswerResponse.newBuilder().setStatus(status).build());
        responseObserver.onCompleted();
    }

    @Override
    public void registerSmsStep3Register(SmsStep3RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        RegisterResponse.Builder builder = RegisterResponse.newBuilder();
        try {
            String mobile = Hope.that(request.getMobile()).named("mobile")
                    .isNotNullOrEmpty()
                    .matches("^(13|14|15|16|17|18|19)\\d{9}$")
                    .value();
            String password = Hope.that(request.getPassword()).named("password")
                    .isNotNullOrEmpty()
                    .isTrue(n -> n.length() >= 6 && n.length() <= 32, "密码长度为6～32位")
                    .value();
            String ssmCode = Hope.that(request.getSmscode()).named("ssmCode")
                    .isNotNullOrEmpty()
                    .value();
            User user = userRepository.findByMobile(mobile);
            if (user != null && !user.getActive()) {
                Status status = Status.newBuilder()
                        .setCode(Status.Code.FAILED_PRECONDITION)
                        .setDetails("用户已禁用")
                        .build();
                builder.setStatus(status);
            } else {
                if (smsActive) {
                    if (msgSend.verify(mobile, ssmCode)) {
                        createUser(user, mobile, password, builder);
                    } else {
                        Status status = Status.newBuilder()
                                .setCode(Status.Code.FAILED_PRECONDITION)
                                .setDetails("验证失败，请重试")
                                .build();
                        builder.setStatus(status);
                    }
                } else {
                    createUser(user, mobile, password, builder);
                }
            }
        } catch (UncheckedValidationException e) {
            Status status = Status.newBuilder()
                    .setCode(Status.Code.INVALID_ARGUMENT)
                    .setDetails(e.getMessage())
                    .build();
            builder.setStatus(status);
        } catch (DuplicateKeyException e) {
            Status status = Status.newBuilder()
                    .setCode(Status.Code.ALREADY_EXISTS)
                    .setDetails("用户已注册,请返回登录")
                    .build();
            builder.setStatus(status);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    private SigninResponse.Builder signinSms(User user, SigninResponse.Builder builder) {
        //calc expire time
        Date date = expireDate();
        Status status = Status.newBuilder()
                .setCode(Status.Code.OK)
                .setDetails("登录成功")
                .build();
        String compactJws = generate(user.getUuid(), new Date(), date);
        return builder.setStatus(status)
                .setExpiresIn(String.valueOf(date.getTime()))
                .setAccessToken(AuthInterceptor.AUTHENTICATION_SCHEME + compactJws);
    }

    private RegisterResponse.Builder createUser(User user, String mobile, String password, RegisterResponse.Builder builder) {
        Date now = new Date();
        String detail;
        if (user == null) {
            user = User.builder()
                    .active(true)
                    .createdDate(now)
                    .updatedDate(now)
                    .mobile(mobile)
                    .password(DigestUtils.sha256Hex(password))
                    .build();
            detail = "注册成功";
        } else {
            user.setPassword(DigestUtils.sha256Hex(password));
            user.setUpdatedDate(now);
            detail = "修改密码成功";
        }
        User savedUser = (User) userRepository.save(user);
        upsesrtAccounts(savedUser);
        //calc expire time
        Date date = expireDate();
        String compactJws = generate(savedUser.getUuid(), now, date);
        Status status = Status.newBuilder()
                .setCode(Status.Code.OK)
                .setDetails(detail)
                .build();
        return builder.setStatus(status)
                .setExpiresIn(String.valueOf(date.getTime()))
                .setAccessToken(compactJws);
    }

    private String generate(String uuid, Date issuedAt, Date expiredDate) {
        //store jwt id;
        Login login = Login.builder()
                .active(true)
                .userUuid(uuid)
                .expireDate(expiredDate)
                .createdDate(issuedAt)
                .updatedDate(issuedAt)
                .build();
        Login savedLogin = (Login) loginRepository.save(login);
        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuedAt(issuedAt) // need create login record
                .setSubject(uuid)
                .setId(savedLogin.getUuid())
                .compressWith(CompressionCodecs.GZIP)
                .signWith(SignatureAlgorithm.HS512, signinKey)
                .setExpiration(expiredDate)
                .compact();
    }

    private Date expireDate() {
        //time calc
        Instant now = Instant.now();
        Instant expireDate = now.plus(Duration.ofMinutes(expiredInMinutes));
        return Date.from(expireDate);
    }

    private void upsesrtAccounts(User user){
        accountingClient.upsesrtAccounts(user, (AccountResponse reponse) -> {
            if (!reponse.getStatus().getCode().equals(Status.Code.OK)){
               log.error("创建用户相关账户错误，{}",reponse.getStatus());
            }
        },new GrpcApiCallback() {
            @Override
            public void onGrpcApiError(Status status) {
                log.error("创建用户相关账户错误，{}",status);
            }

            @Override
            public void onGrpcApiCompleted() {
                log.error("-------complete-----");
            }
        });

    }
}
