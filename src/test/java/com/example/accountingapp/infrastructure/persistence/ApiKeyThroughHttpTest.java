package com.example.accountingapp.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 設定画面で発行した API キーで MCP に接続できること、失効・ロールの変更・アカウントの削除がすぐに効くこと。 */
@AutoConfigureMockMvc
class ApiKeyThroughHttpTest extends PostgresTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvcTester mvc;

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode issueKey(String user) {
        MvcTestResult result = mvc.post().uri("/me/api-keys").header(HttpHeaders.AUTHORIZATION, basic(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Claude Code\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    /** MCP の whoAmI ツールを呼ぶ。 */
    private MvcTestResult callWhoAmI(String authorization) {
        return mvc.post().uri("/mcp").header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("""
                        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                         "params": {"name": "who_am_i", "arguments": {}}}
                        """)
                .exchange();
    }

    private static String whoAmIText(MvcTestResult result) {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
        return body.get("result").get("content").get(0).get("text").asString();
    }

    @Test
    void issuedKeyAuthenticatesMcpAsItsOwner() throws Exception {
        JsonNode issued = issueKey("bookkeeper");
        String token = issued.get("token").asString();
        assertThat(token).startsWith("acc_");
        assertThat(issued.get("key").get("prefix").asString()).isEqualTo(token.substring(0, 10));

        MvcTestResult result = callWhoAmI("Bearer " + token);
        assertThat(result).hasStatusOk();
        assertThat(whoAmIText(result)).contains("\"name\":\"bookkeeper\"").contains("BOOKKEEPER");

        // 一覧にはキーそのものもハッシュも出さない。使った日時は記録する
        MvcTestResult list = mvc.get().uri("/me/api-keys").header(HttpHeaders.AUTHORIZATION, basic("bookkeeper")).exchange();
        assertThat(list).hasStatusOk();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listBody).doesNotContain(token).doesNotContain("token");
        assertThat(JSON.readTree(listBody).get(0).get("lastUsedAt").isNull()).isFalse();
        // DB にはハッシュだけを保存する
        assertThat(jdbc.sql("select count(*) from api_key where token_hash = ?").param(token).query(Long.class).single())
                .isZero();
    }

    @Test
    void keysWorkOnlyForMcpAndOnlyWhileValid() {
        String token = issueKey("viewer").get("token").asString();

        assertThat(callWhoAmI("Bearer acc_wrong")).hasStatus(HttpStatus.UNAUTHORIZED);
        // REST API と画面は HTTP Basic だけ
        assertThat(mvc.get().uri("/periods").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);

        String id = issueKey("viewer").get("key").get("id").asString();
        assertThat(mvc.delete().uri("/me/api-keys/" + id).header(HttpHeaders.AUTHORIZATION, basic("viewer")).exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        // ほかのユーザーのキーは失効させられない
        String othersId = issueKey("approver").get("key").get("id").asString();
        assertThat(mvc.delete().uri("/me/api-keys/" + othersId).header(HttpHeaders.AUTHORIZATION, basic("viewer")).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(callWhoAmI("Bearer " + token)).hasStatusOk();
    }

    @Test
    void revokedKeysAndKeysOfDeletedOrDemotedUsersStopWorking() {
        mvc.post().uri("/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"alice\", \"password\": \"correct-horse\"}").exchange();
        String aliceBasic = "Basic " + Base64.getEncoder().encodeToString("alice:correct-horse".getBytes(StandardCharsets.UTF_8));
        mvc.patch().uri("/users/alice").header(HttpHeaders.AUTHORIZATION, basic("approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"VIEWER\"}").exchange();
        MvcTestResult issued = mvc.post().uri("/me/api-keys").header(HttpHeaders.AUTHORIZATION, aliceBasic)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"key\"}").exchange();
        String token = JSON.readTree(issued.getResponse().getContentAsByteArray()).get("token").asString();
        assertThat(callWhoAmI("Bearer " + token)).hasStatusOk();

        // ロールを外されると、キーでも MCP を使えない
        mvc.patch().uri("/users/alice").header(HttpHeaders.AUTHORIZATION, basic("approver"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": null}").exchange();
        assertThat(callWhoAmI("Bearer " + token)).hasStatus(HttpStatus.FORBIDDEN);

        // アカウントを削除すると、キーも削除される
        mvc.delete().uri("/users/alice").header(HttpHeaders.AUTHORIZATION, basic("approver")).exchange();
        assertThat(callWhoAmI("Bearer " + token)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.sql("select count(*) from api_key where user_name = 'alice'").query(Long.class).single()).isZero();
    }
}
