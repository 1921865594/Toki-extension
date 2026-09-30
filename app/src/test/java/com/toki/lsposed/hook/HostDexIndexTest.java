package com.toki.lsposed.hook;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** 用实际 DEX 验证跨名称匹配、Split 覆盖、歧义拒绝与缓存身份变化。 */
public class HostDexIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /**
     * 创建返回常量的独立 DEX 类。
     * @param name DEX 类描述符。
     * @param value 返回常量。
     * @return 测试 ClassDef。
     * Callers: 本类测试。
     */
    private ClassDef type(String name, int value) {
        var body = new ImmutableMethodImplementation(1,
            List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, value),
                new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of());
        var method = new ImmutableMethod(name, "value", List.of(), "I", 9, Set.of(), Set.of(), body);
        return new ImmutableClassDef(name, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(), List.of(method));
    }

    /**
     * 写入 JUnit 独立临时 APK。
     * @param types 要写入的类。
     * @return 临时 APK 路径。
     * @throws IOException 测试文件创建失败。
     * Callers: 本类测试。
     */
    private String apk(ClassDef... types) throws IOException {
        var pool = new DexPool(Opcodes.getDefault());
        for (ClassDef type : types) pool.internClass(type);
        var store = new MemoryDataStore();
        pool.writeTo(store);
        File target = temporary.newFile();
        try (var zip = new ZipOutputStream(new FileOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(store.getData());
            zip.closeEntry();
        }
        store.close();
        return target.getAbsolutePath();
    }

    /** 构造单条特征。@param type 样本类。@return 规则行。Callers: 本类测试。 */
    private String rule(ClassDef type) {
        return "TEST\tplay\t" + HostDexIndex.shape(type) + "\t" + HostDexIndex.literals(type) +
            "\t" + HostDexIndex.digest("") + "\t-";
    }

    /**
     * 替换测试规则的成员绑定，不改变类和指令特征。
     * @param type 测试类。
     * @param bindings 业务成员契约。
     * @return 完整规则行。
     * Callers: 本类成员契约测试。
     */
    private String boundRule(ClassDef type, String bindings) {
        String rule = rule(type);
        return rule.substring(0, rule.length() - 1) + bindings;
    }

    /** 验证业务角色与完整方法描述一并保存。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void memberContractIsPublishedWithResolvedClass() throws IOException {
        var sample = type("LX/A;", 1);
        var result = HostDexIndex.scan(List.of(apk(sample)), boundRule(sample, "decision=value()I"));
        assertEquals("value()I", result.getProperty("member.TEST.decision"));
        assertEquals("X.A|play", result.getProperty("TEST"));
    }

    /** 验证方法返回类型不符时不发布类或成员。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void mismatchedReturnTypeRejectsContract() throws IOException {
        var sample = type("LX/A;", 1);
        var result = HostDexIndex.scan(List.of(apk(sample)), boundRule(sample, "decision=value()Z"));
        assertFalse(result.containsKey("TEST"));
        assertFalse(result.containsKey("member.TEST.decision"));
        assertEquals("候选数量=0", result.getProperty("error.TEST"));
    }

    /** 验证参数改变和方法缺失均不能通过成员校验。@return 无。Callers: JUnit。 */
    @Test public void missingMemberAndChangedParametersAreRejected() {
        var sample = type("LX/A;", 1);
        assertFalse(HostDexIndex.matchesBindings(sample, "decision=value(Z)I"));
        assertFalse(HostDexIndex.matchesBindings(sample, "decision=missing()I"));
    }

    /** 验证同一候选具有冲突角色时明确拒绝，而不是选择第一条规则。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void conflictingBindingsAreRejected() throws IOException {
        var sample = type("LX/A;", 1);
        String rules = boundRule(sample, "decision=value()I") + "\n" + boundRule(sample, "other=value()I");
        var result = HostDexIndex.scan(List.of(apk(sample)), rules);
        assertEquals("成员契约存在冲突", result.getProperty("error.TEST"));
        assertFalse(result.containsKey("TEST"));
        assertFalse(result.containsKey("member.TEST.decision"));
    }

    /** 验证同类多渠道规则的相同角色不被误判为冲突。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void identicalBindingsAcrossProfilesAreAccepted() throws IOException {
        var sample = type("LX/A;", 1);
        String rule = boundRule(sample, "decision=value()I");
        var result = HostDexIndex.scan(List.of(apk(sample)), rule + "\n" + rule.replace("\tplay\t", "\tplay4703\t"));
        assertTrue(result.containsKey("TEST"));
        assertEquals("value()I", result.getProperty("member.TEST.decision"));
    }

    /** 验证新版构建标识不会被转换为其他渠道。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void profileIsNotCollapsedIntoChannel() throws IOException {
        var sample = type("LX/A;", 1);
        var result = HostDexIndex.scan(List.of(apk(sample)), rule(sample).replace("\tplay\t", "\tplay4703\t"));
        assertEquals("X.A|play4703", result.getProperty("TEST"));
    }

    /** 验证重复的角色不能覆盖已有成员契约。@return 无。Callers: JUnit。 */
    @Test public void duplicateRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HostDexIndex.matchesBindings(
            type("LX/A;", 1), "decision=value()I|decision=value()I"));
    }

    /** 验证实际字段类型必须匹配。@return 无。Callers: JUnit。 */
    @Test public void fieldBindingChecksItsType() {
        var field = new ImmutableField("LX/A;", "value", "I", 1, null, Set.of(), Set.of());
        var sample = new ImmutableClassDef("LX/A;", 1, "Ljava/lang/Object;", List.of(), null,
            Set.of(), List.of(field), List.of());
        assertTrue(HostDexIndex.matchesBindings(sample, "state=value:I"));
        assertFalse(HostDexIndex.matchesBindings(sample, "state=value:Z"));
    }

    /** 验证混淆名称变化仍可定位。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void renamedClassIsFound() throws IOException {
        var sample = type("LX/Original;", 1);
        var result = HostDexIndex.scan(List.of(apk(type("LX/Renamed;", 1))), rule(sample));
        assertEquals("X.Renamed|play", result.getProperty("TEST"));
    }

    /** 验证语义不同的同结构类被拒绝。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void differentConstantIsRejected() throws IOException {
        var result = HostDexIndex.scan(List.of(apk(type("LX/A;", 2))), rule(type("LX/B;", 1)));
        assertFalse(result.containsKey("TEST"));
        assertTrue(result.containsKey("error.TEST"));
    }

    /** 验证不从多个同结构、同语义候选任取一个。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void ambiguityIsRejected() throws IOException {
        var result = HostDexIndex.scan(List.of(apk(type("LX/A;", 1), type("LX/B;", 1))), rule(type("LX/C;", 1)));
        assertFalse(result.containsKey("TEST"));
        assertEquals("候选数量=2", result.getProperty("error.TEST"));
    }

    /** 验证基础包没有目标时仍扫描 Split。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void targetInSplitIsFound() throws IOException {
        String base = apk(type("LX/A;", 2));
        String split = apk(type("LX/B;", 1));
        var result = HostDexIndex.scan(List.of(base, split), rule(type("LX/C;", 1)));
        assertEquals("X.B|play", result.getProperty("TEST"));
    }

    /** 验证 Split 内容与集合变化使缓存身份变化，路径顺序不影响身份。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void identityIncludesAllCodeSplits() throws IOException {
        String base = apk(type("LX/A;", 1));
        String split = apk(type("LX/B;", 2));
        String changed = apk(type("LX/B;", 3));
        assertNotEquals(HostDexIndex.identity(List.of(base)), HostDexIndex.identity(List.of(base, split)));
        assertNotEquals(HostDexIndex.identity(List.of(base, split)), HostDexIndex.identity(List.of(base, changed)));
        assertEquals(HostDexIndex.identity(List.of(base, split)), HostDexIndex.identity(List.of(split, base)));
    }

    /** 验证相同代码安装到不同路径不改变身份。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void identityIgnoresApkLocation() throws IOException {
        String original = apk(type("LX/A;", 1));
        File relocated = new File(temporary.newFolder(), "base.apk");
        java.nio.file.Files.copy(new File(original).toPath(), relocated.toPath());
        assertEquals(HostDexIndex.identity(List.of(original)),
            HostDexIndex.identity(List.of(relocated.getAbsolutePath())));
    }

    /** 验证不含代码的资源分包不会触发方法重查。@return 无。@throws IOException 文件错误。Callers: JUnit。 */
    @Test public void identityIgnoresResourceOnlySplit() throws IOException {
        String base = apk(type("LX/A;", 1));
        File resources = temporary.newFile();
        try (var zip = new ZipOutputStream(new FileOutputStream(resources))) {
            zip.putNextEntry(new ZipEntry("resources.arsc"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        assertEquals(HostDexIndex.identity(List.of(base)),
            HostDexIndex.identity(List.of(base, resources.getAbsolutePath())));
    }

    /** 验证真实DEX扫描进度不倒退、到达总量且包含方法名。@throws IOException 文件错误。Returns: 无。Callers: JUnit。 */
    @Test public void progressCoversSplitsAndMethods() throws IOException {
        List<Integer> completed = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        var result = HostDexIndex.scan(List.of(apk(type("LX/A;", 2)), apk(type("LX/B;", 1))),
            rule(type("LX/C;", 1)), (done, total, label) -> {
                completed.add(done); totals.add(total); labels.add(label);
            });
        assertEquals("X.B|play", result.getProperty("TEST"));
        assertEquals(Integer.valueOf(0), completed.get(0));
        assertEquals(1, new HashSet<>(totals).size());
        for (int i = 1; i < completed.size(); i++) assertTrue(completed.get(i) >= completed.get(i - 1));
        assertEquals(totals.get(0), completed.get(completed.size() - 1));
        assertTrue(labels.stream().anyMatch(label -> label.contains("方法：value")));
    }
}
