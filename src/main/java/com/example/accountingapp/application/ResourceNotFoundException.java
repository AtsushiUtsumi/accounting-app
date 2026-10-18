package com.example.accountingapp.application;

/** 指定されたリソース(期・科目・仕訳など)が見つからない。HTTP 404 に変換する。 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
