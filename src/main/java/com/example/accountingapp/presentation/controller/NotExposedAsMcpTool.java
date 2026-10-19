package com.example.accountingapp.presentation.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP のツールにしないエンドポイントの目印。コントローラーの機能は原則としてすべて MCP のツールにする
 * ({@code McpCoverageTest} が確かめる)が、人がブラウザから行う操作だけは例外にする。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NotExposedAsMcpTool {

    /** ツールにしない理由。 */
    String value();
}
