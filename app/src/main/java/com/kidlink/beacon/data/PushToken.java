package com.kidlink.beacon.data;

/**
 * 一条 PushPlus 推送通道。
 *
 * 支持配多个（爸爸、妈妈、爷爷各一个），每个可以单独开关，
 * 开着的一起推，关掉的不推。
 */
public class PushToken {

    /** 自定义名称，例如「爸爸的微信」 */
    public String name;
    /** PushPlus 的 token */
    public String token;
    /** 是否参与推送 */
    public boolean enabled;

    public PushToken(String name, String token, boolean enabled) {
        this.name = name == null ? "" : name.trim();
        this.token = token == null ? "" : token.trim();
        this.enabled = enabled;
    }

    /** 名字空的时候退回用 token 尾部兜底显示 */
    public String displayName() {
        if (name != null && !name.isEmpty()) return name;
        return token == null || token.isEmpty() ? "" : "未命名";
    }

    public boolean isValid() {
        return token != null && !token.isEmpty();
    }

    public PushToken copy() {
        return new PushToken(name, token, enabled);
    }
}
