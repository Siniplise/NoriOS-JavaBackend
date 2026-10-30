package com.nori.backend.config;

import java.util.function.Function;

/**
 * 密钥读取的唯一入口。
 *
 * <p>铁律：只从环境变量读，代码、JSON、日志、命令行参数里都不允许出现明文；
 * 缺了就<b>显式抛错</b>，不静默降级成空串——否则排障时会误以为是上游挂了。
 *
 * <p>配置文件里只有 {@link NoriConfig.Secrets}（环境变量名），
 * 所以「读配置」和「读密钥」是两件必须分开的事，这个类就是那条分界线。
 */
public final class SecretRef {

    /** 从进程环境变量读取；缺失即抛。 */
    public static String resolve(String envName) {
        return resolve(System::getenv, envName);
    }

    /** 可注入版本（测试用）。 */
    public static String resolve(Function<String, String> env, String envName) {
        if (envName == null || envName.isBlank()) {
            throw new IllegalStateException("未配置密钥的环境变量名（secrets.*KeyEnv 为空）");
        }
        String value = env.apply(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    envName + " 未设置：密钥只从环境变量读取，代码与配置文件里都不允许出现明文");
        }
        return value;
    }

    /** 探测用（比如启动时决定 TTS 能不能启），不抛异常。 */
    public static boolean isPresent(Function<String, String> env, String envName) {
        if (envName == null || envName.isBlank()) {
            return false;
        }
        String value = env.apply(envName);
        return value != null && !value.isBlank();
    }

    /** 日志里展示密钥时唯一允许的形式：前 4 位 + 长度，其余全遮。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "<empty>";
        }
        String head = secret.length() <= 4 ? "" : secret.substring(0, 4);
        return head + "***(" + secret.length() + ")";
    }

    private SecretRef() {
        throw new AssertionError("no instances");
    }
}
