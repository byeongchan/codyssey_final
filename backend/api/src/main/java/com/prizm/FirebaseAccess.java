package com.prizm;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Date;

/** Explicit cloud credentials or isolated local emulator access. */
@Component
public class FirebaseAccess {
    private final String mode;
    private final String project;
    private FirebaseApp app;
    public FirebaseAccess(@Value("${prizm.firebase.mode}") String mode, @Value("${prizm.firebase.project-id}") String project) {
        this.mode = mode; this.project = project;
    }
    private synchronized FirebaseApp app() {
        if ("cloud".equals(mode)) {
            if (project.isBlank() || project.startsWith("demo-")
                    || !"reef-danawa".equals(System.getenv().getOrDefault("DATA_PROVIDER", "reef-danawa"))
                    || System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null || System.getenv("FIRESTORE_EMULATOR_HOST") != null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "실제 Firebase 프로젝트와 에뮬레이터 변수 제거를 확인하세요");
            if (app == null) {
                try {
                    app = FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(project)
                        .setCredentials(GoogleCredentials.getApplicationDefault()).build(), "prizm-cloud");
                } catch (java.io.IOException ex) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase 서버 자격 증명을 확인하세요");
                }
            }
            return app;
        }
        if (!"emulator".equals(mode) || !project.startsWith("demo-")
                || !"127.0.0.1:9099".equals(System.getenv("FIREBASE_AUTH_EMULATOR_HOST"))
                || !"127.0.0.1:8085".equals(System.getenv("FIRESTORE_EMULATOR_HOST")))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase 에뮬레이터 환경 변수를 확인하세요");
        if (app == null) {
            // 로컬 전용 가짜 자격 증명. ADC/서비스 계정 조회나 토큰 갱신을 수행하지 않는다.
            var credentials = GoogleCredentials.create(new AccessToken("owner", new Date(253402300799000L)));
            app = FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(project).setCredentials(credentials).build(), "prizm-local");
        }
        return app;
    }
    public FirebaseAuth auth() { return FirebaseAuth.getInstance(app()); }
    public Firestore firestore() { return FirestoreClient.getFirestore(app()); }
    @PreDestroy public synchronized void close() { if (app != null) app.delete(); }
}
