package com.example.accountingapp.presentation.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * コントローラーで公開している機能が、すべて MCP のツールとしても公開されていること。
 * {@code presentation.controller.<機能>} と {@code presentation.mcp.<機能>} を同じ並びにしているので、機能ごとに
 * エンドポイントの数とツールの数を比べる(エンドポイントを足したらツールも足す)。
 */
class McpCoverageTest {

    private static final String PRESENTATION = "com.example.accountingapp.presentation";

    @Test
    void everyControllerEndpointHasAnMcpTool() {
        Map<String, Integer> endpoints = countByFeature(PRESENTATION + ".controller",
                new AnnotationTypeFilter(RestController.class),
                method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
        Map<String, Integer> tools = countByFeature(PRESENTATION + ".mcp",
                new AssignableTypeFilter(McpTools.class),
                method -> method.isAnnotationPresent(Tool.class));

        assertThat(endpoints).isNotEmpty();
        assertThat(tools).isEqualTo(endpoints);
    }

    /** 機能のパッケージ名(journal など)ごとに、条件に合うメソッドの数を数える。 */
    private static Map<String, Integer> countByFeature(String basePackage, TypeFilter filter, Predicate<Method> counted) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(filter);
        Map<String, Integer> counts = new TreeMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            String feature = type.getPackageName().substring(basePackage.length() + 1);
            int count = (int) Arrays.stream(type.getDeclaredMethods()).filter(counted).count();
            counts.merge(feature, count, Integer::sum);
        }
        return counts;
    }
}
