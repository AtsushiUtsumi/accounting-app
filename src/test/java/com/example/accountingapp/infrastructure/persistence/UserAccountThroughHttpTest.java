package com.example.accountingapp.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * サインアップ → 承認者がロールを付ける → アカウントの削除を、本物の認証(HTTP Basic)と DB を通して確かめる。
 * ユーザーは application.yml の開発用ユーザー(viewer / bookkeeper / approver)と、サインアップしたユーザー。
 */
@AutoConfigureMockMvc
class UserAccountThroughHttpTest extends PostgresTestSupport {

    @Autowired
    MockMvcTester mvc;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private MvcTestResult signUp(String name, String password) {
        return mvc.post().uri("/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "password": "%s"}
                        """.formatted(name, password))
                .exchange();
    }

    private MvcTestResult get(String path, String user, String password) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, basic(user, password)).exchange();
    }

    private MvcTestResult changeRole(String name, String role) {
        return mvc.patch().uri("/users/" + name).header(HttpHeaders.AUTHORIZATION, basic("approver", "approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(role == null ? "{\"role\": null}" : "{\"role\": \"" + role + "\"}")
                .exchange();
    }

    @Test
    void signedUpUserCanUseTheBooksOnlyAfterAnApproverGrantsARole() {
        assertThat(signUp("alice", "correct-horse")).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.role").isNull();

        assertThat(get("/me", "alice", "correct-horse")).hasStatusOk()
                .bodyJson().extractingPath("$.roles").asArray().isEmpty();
        assertThat(get("/periods", "alice", "correct-horse")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/periods", "alice", "wrong-password")).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(changeRole("alice", "VIEWER")).hasStatusOk();
        assertThat(get("/periods", "alice", "correct-horse")).hasStatusOk();
        // 記帳は記帳者以上
        assertThat(mvc.post().uri("/journals").header(HttpHeaders.AUTHORIZATION, basic("alice", "correct-horse"))
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(changeRole("alice", null)).hasStatusOk();
        assertThat(get("/periods", "alice", "correct-horse")).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void signUpRejectsTakenAndMalformedNames() {
        assertThat(signUp("alice", "correct-horse")).hasStatus(HttpStatus.CREATED);
        assertThat(signUp("alice", "another-password")).hasStatus(HttpStatus.CONFLICT);
        // 設定ファイルのユーザーと同じ名前
        assertThat(signUp("approver", "correct-horse")).hasStatus(HttpStatus.CONFLICT);
        assertThat(signUp("a", "correct-horse")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(signUp("bob", "short")).hasStatus(HttpStatus.BAD_REQUEST);
        // パスワードは平文で保存しない
        assertThat(jdbc.sql("select password from app_user where name = 'alice'").query(String.class).single())
                .startsWith("{bcrypt}").doesNotContain("correct-horse");
    }

    @Test
    void userCanDeleteTheirOwnAccount() {
        signUp("alice", "correct-horse");

        assertThat(mvc.delete().uri("/me").header(HttpHeaders.AUTHORIZATION, basic("alice", "correct-horse")).exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get("/me", "alice", "correct-horse")).hasStatus(HttpStatus.UNAUTHORIZED);
        // 設定ファイルのユーザーは削除できない
        assertThat(mvc.delete().uri("/me").header(HttpHeaders.AUTHORIZATION, basic("viewer", "viewer")).exchange())
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void onlyApproversManageUsers() {
        signUp("alice", "correct-horse");

        assertThat(get("/users", "bookkeeper", "bookkeeper")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get("/users", "approver", "approver")).hasStatusOk()
                .bodyJson().extractingPath("$[*].name").asArray().contains("alice", "approver", "bookkeeper", "viewer");
        assertThat(mvc.delete().uri("/users/viewer").header(HttpHeaders.AUTHORIZATION, basic("approver", "approver"))
                .exchange()).hasStatus(HttpStatus.CONFLICT);
        assertThat(mvc.delete().uri("/users/alice").header(HttpHeaders.AUTHORIZATION, basic("approver", "approver"))
                .exchange()).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get("/me", "alice", "correct-horse")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
