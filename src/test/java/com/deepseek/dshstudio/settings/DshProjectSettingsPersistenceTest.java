package com.deepseek.dshstudio.settings;

import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 回归测试：项目级设置类必须能被平台的 XML state 序列化器反序列化。
 * <p>
 * 为什么单独一个测试类：{@link DshProjectSettingsTest} 刻意不碰任何 IDE 平台类
 * （纯字段逻辑，project 传 {@code null}），而这个测试恰恰要验证<b>平台视角</b>的约定。
 * <p>
 * <b>背景（0.3.3 的事故）</b>：{@code DshProjectSettings} 把自己当作
 * {@code PersistentStateComponent} 的 state 类型，但只提供了 {@code (Project)} 构造函数。
 * 平台在加载 {@code workspace.xml} 时走的是
 * {@code DefaultStateSerializer.deserializeState(element, stateClass)}，它需要一个
 * <b>无参构造</b> 来 new 出 state 对象，否则抛
 * {@code XmlSerializationException: Cannot deserialize class ...DshProjectSettings}，
 * 组件初始化失败 —— 表现就是「点 ▶ 毫无反应」（{@code startServer()} 第一行取项目设置就中断）。
 * <p>
 * 这里用公开的 {@link XmlSerializer}（平台 state 反序列化底层用的就是同一套 BeanBinding），
 * 所以只要有人再把这个无参构造删掉，这个测试会立刻失败。
 */
public class DshProjectSettingsPersistenceTest {

    /** 平台反序列化 state 的最基本要求：能 new 出实例并把 option 绑到字段上。 */
    @Test
    public void platformCanDeserializeState() {
        Element component = new Element("component");
        component.setAttribute("name", "DshStudioProjectSettings");
        Element option = new Element("option");
        option.setAttribute("name", "serverUrl");
        option.setAttribute("value", "http://127.0.0.1:3099");
        component.addContent(option);

        DshProjectSettings state = XmlSerializer.deserialize(component, DshProjectSettings.class);

        assertEquals("http://127.0.0.1:3099", state.serverUrl);
    }

    /** 写出去再读回来，六个持久化字段都要原样回来（含 allocatedPort / migrated）。 */
    @Test
    public void stateRoundTripsThroughSerializer() {
        DshProjectSettings original = new DshProjectSettings(null);
        original.serverUrl = "http://127.0.0.1:3098";
        original.startPort = 3098;
        original.allocatedPort = 3099;
        original.workingDirectory = "D:/tmp/work";
        original.serverAuthToken = "token-abc";
        original.migrated = true;

        Element element = new Element("component");
        XmlSerializer.serializeInto(original, element);
        DshProjectSettings back = XmlSerializer.deserialize(element, DshProjectSettings.class);

        assertEquals(original.serverUrl, back.serverUrl);
        assertEquals(original.startPort, back.startPort);
        assertEquals(original.allocatedPort, back.allocatedPort);
        assertEquals(original.workingDirectory, back.workingDirectory);
        assertEquals(original.serverAuthToken, back.serverAuthToken);
        assertEquals(original.migrated, back.migrated);
    }
}
