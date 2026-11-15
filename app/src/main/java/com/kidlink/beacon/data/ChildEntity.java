package com.kidlink.beacon.data;

/**
 * 一个「娃」—— 首页一块面板就对应一个娃。
 *
 * 为什么要有这一层：
 * 信标是跟着**包**走的，不是跟着娃走的。书包上放一颗、奥数包上再放一颗，
 * 两颗信标其实是同一个娃。按信标显示是「两块各算各的」，按娃显示才对得上
 * 家长脑子里的那件事 ——「我现在离许小鱼多远」。
 *
 * 一个娃下面可以挂任意多颗信标，谁信号强就拿谁当「当前」。
 */
public class ChildEntity {

    private long id;
    private String name;
    private int sortOrder;

    public ChildEntity() {
    }

    public ChildEntity(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name == null ? "" : name.trim(); }
    public void setName(String name) { this.name = name == null ? "" : name.trim(); }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    /** 名字为空时退回「某娃」，避免推送里出现「接到了」这种断句 */
    public String displayName() {
        return getName().isEmpty() ? "孩子" : getName();
    }

    @Override
    public String toString() {
        return "ChildEntity{id=" + id + ", name='" + getName() + "'}";
    }
}
