package com.toki.lsposed.hook;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** 纯 Java DEX 特征索引；只读取代码，不加载或执行候选宿主类。 */
public final class HostDexIndex {
    private static final Pattern OBFUSCATED = Pattern.compile("LX/[^;]+;|Lkotlin/jvm/internal/[A-Z][^;]+;");
    private static final ProgressListener NO_PROGRESS = (completed, total, target) -> {};

    /** 扫描工作进度；每个 DEX 和每个符号各为 1000 单位，不表示预计耗时。 */
    @FunctionalInterface
    public interface ProgressListener {
        /**
         * 接收已经完成的实际扫描工作与当前类或方法。
         * @param completed 已完成的工作单位。
         * @param total 本次两轮 DEX 遍历与符号校验的总单位。
         * @param target 当前阶段、类名或已匹配的方法集合。
         * Returns: 无。
         * Callers: visit、scan。
         */
        void onProgress(int completed, int total, String target);
    }

    /**
     * 将混淆类型替换为结构占位符，保留所有未混淆业务类型与数组维度。
     * @param value DEX 类型或成员描述。
     * @return 规范化描述。
     * Callers: shape。
     */
    private static String normalize(String value) {
        return OBFUSCATED.matcher(value).replaceAll("L_obfuscated_;");
    }

    /**
     * 计算字段及方法的完整结构指纹；保留方法名和访问属性以拒绝成员契约变化。
     * @param type 候选 DEX 类。
     * @return SHA-256 结构指纹。
     * Callers: scan、离线规则生成器。
     */
    public static String shape(ClassDef type) {
        List<String> parts = new ArrayList<>();
        parts.add("super:" + normalize(String.valueOf(type.getSuperclass())));
        for (String value : type.getInterfaces()) parts.add("interface:" + normalize(value));
        for (var field : type.getFields()) parts.add("f:" + field.getName() + ":" +
                normalize(field.getType()) + ":" + field.getAccessFlags());
        for (Method method : type.getMethods()) {
            StringBuilder signature = new StringBuilder("m:").append(method.getName()).append('(');
            for (CharSequence value : method.getParameterTypes()) signature.append(normalize(value.toString()));
            signature.append(')').append(normalize(method.getReturnType())).append(':').append(method.getAccessFlags());
            parts.add(signature.toString());
        }
        Collections.sort(parts);
        return digest(String.join("\n", parts));
    }

    /**
     * 计算候选类的规范化指令语义，包含数值常量及成员引用，忽略混淆类型名称。
     * @param type 候选 DEX 类。
     * @return 按方法排序的指令语义 SHA-256。
     * Callers: scan、离线规则生成器。
     */
    public static String literals(ClassDef type) {
        Set<String> values = new TreeSet<>();
        for (Method method : type.getMethods()) {
            if (method.getImplementation() == null) continue;
            StringBuilder body = new StringBuilder(method.getName()).append(':');
            for (var instruction : method.getImplementation().getInstructions()) {
                body.append(instruction.getOpcode().name()).append(':');
                if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str) {
                    body.append(str.getString().length()).append(':').append(str.getString());
                } else if (instruction instanceof ReferenceInstruction ref) {
                    body.append(normalize(ref.getReference().toString()));
                }
                if (instruction instanceof WideLiteralInstruction literal) body.append(literal.getWideLiteral());
                body.append('\n');
            }
            values.add(body.toString());
        }
        return digest(String.join("\n", values));
    }

    /**
     * 提取静态初始化直接引用的类型，用于区分结构相同的配置开关。
     * @param type 候选类。
     * @return 不包含自身的引用类型集合。
     * Callers: linked、scan。
     */
    public static Set<String> dependencies(ClassDef type) {
        Set<String> result = new TreeSet<>();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("<clinit>") || method.getImplementation() == null) continue;
            for (var instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof TypeReference target &&
                    !target.getType().equals(type.getType())) result.add(target.getType());
            }
        }
        return result;
    }

    /**
     * 获取候选初始化引用的一层类型字符串指纹，不保留整份 DEX 对象。
     * @param paths 所有代码包。
     * @param dependencies 候选类型到其依赖类型的映射。
     * @return 候选类型到链接语义指纹的映射。
     * @throws IOException 代码读取错误。
     * Callers: scan、离线规则生成器。
     */
    public static Map<String, String> linked(List<String> paths, Map<String, Set<String>> dependencies) throws IOException {
        return linked(paths, dependencies, NO_PROGRESS, 0, 1);
    }

    /**
     * 解析依赖指纹并报告第二轮遍历进度。
     * @param paths 全部代码包。
     * @param dependencies 候选及依赖映射。
     * @param progress 只读进度监听器。
     * @param offset 之前完成的工作单位。
     * @param total 全扫描工作单位。
     * @return 候选的链接语义指纹。
     * @throws IOException DEX读取错误。
     * Callers: linked、scan。
     */
    private static Map<String, String> linked(List<String> paths, Map<String, Set<String>> dependencies,
            ProgressListener progress, int offset, int total) throws IOException {
        Set<String> wanted = new HashSet<>();
        dependencies.values().forEach(wanted::addAll);
        Map<String, String> values = new HashMap<>();
        visit(paths, type -> { if (wanted.contains(type.getType())) values.put(type.getType(), literals(type)); },
                progress, offset, total, "校验初始化依赖");
        Map<String, String> result = new HashMap<>();
        dependencies.forEach((type, refs) -> {
            Set<String> hashes = new TreeSet<>();
            for (String ref : refs) if (values.containsKey(ref)) hashes.add(values.get(ref));
            result.put(type, digest(String.join("\n", hashes)));
        });
        return result;
    }

    /**
     * 顺序访问每个 APK 的全部 DEX；每次只保留一个 DEX 的字节缓冲。
     * @param paths 基础包和全部 Split 包路径。
     * @param visitor 每个类的只读访问器，不应保留 ClassDef。
     * @throws IOException APK 或 DEX 无法读取。
     * Callers: scan、离线规则生成器。
     */
    public static void visit(List<String> paths, Consumer<ClassDef> visitor) throws IOException {
        visit(paths, visitor, NO_PROGRESS, 0, 1, "读取类结构");
    }

    /**
     * 逐 DEX 遍历并按实际处理的类数报告进度，不增加类加载或额外扫描。
     * @param paths 全部代码包。
     * @param visitor 类访问器。
     * @param progress 进度接收器。
     * @param offset 前序阶段工作单位。
     * @param total 全扫描工作单位。
     * @param phase 当前阶段名称。
     * Returns: 无。
     * @throws IOException APK或DEX读取错误。
     * Callers: visit、linked、scan。
     */
    private static void visit(List<String> paths, Consumer<ClassDef> visitor, ProgressListener progress,
            int offset, int total, String phase) throws IOException {
        int completedDex = 0;
        for (String path : paths) {
            try (ZipFile apk = new ZipFile(path)) {
                var entries = apk.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    byte[] bytes;
                    try (InputStream stream = apk.getInputStream(entry)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) entry.getSize());
                        byte[] chunk = new byte[65536];
                        int count;
                        while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
                        bytes = buffer.toByteArray();
                    }
                    var dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                    var classes = dex.getClasses();
                    int done = 0;
                    for (ClassDef type : classes) {
                        visitor.accept(type);
                        done++;
                        if (done % 256 == 0) progress.onProgress(offset + completedDex * 1000 +
                                (int) (done * 1000L / classes.size()), total,
                                phase + "\n" + describeMethods(type));
                    }
                    completedDex++;
                    progress.onProgress(offset + completedDex * 1000, total,
                            phase + "\n" + new File(path).getName() + " / " + entry.getName());
                }
            }
        }
    }

    /**
     * 标识完整代码集合，覆盖每个 DEX 的签名头、CRC、长度及所属 APK 的代码分组。
     * @param paths 基础包和全部 Split 路径；不含代码的资源包不影响代码身份。
     * @return SHA-256 代码身份；不是 APK 签名证书验证。
     * @throws IOException 包读取失败。
     * Callers: HostSymbols.initialize、离线验证。
     */
    public static String identity(List<String> paths) throws IOException {
        List<String> containers = new ArrayList<>();
        for (String path : paths) {
            List<String> code = new ArrayList<>();
            try (ZipFile apk = new ZipFile(path)) {
                var entries = apk.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    byte[] header = new byte[32];
                    try (DataInputStream stream = new DataInputStream(apk.getInputStream(entry))) { stream.readFully(header); }
                    code.add(entry.getName() + ":" + entry.getSize() + ":" + entry.getCrc() + ":" + hex(header));
                }
            }
            if (!code.isEmpty()) { Collections.sort(code); containers.add(digest(String.join("\n", code))); }
        }
        if (containers.isEmpty()) throw new IOException("宿主代码集合中没有 DEX");
        Collections.sort(containers);
        return digest(String.join("\n", containers));
    }

    /**
     * 对全部代码扫描规则，只返回去重后唯一的候选；零候选和歧义分别记录。
     * @param paths 完整代码包路径。
     * @param rules 每行 symbol、variant、shape、literals、linked、bindings，以制表符分隔。
     * @return symbol 映射到 binaryName|variant；失败记录在 error.symbol。
     * @throws IOException 输入读取失败。
     * Callers: HostSymbols、离线验证器。
     */
    public static Properties scan(List<String> paths, String rules) throws IOException {
        return scan(paths, rules, NO_PROGRESS);
    }

    /**
     * 扫描全部代码并报告两轮遍历与唯一候选校验的实际进度。
     * @param paths 基础包和全部Split。
     * @param rules 特征规则文本。
     * @param progress 当前工作和方法名称的接收器。
     * @return 唯一候选及明确失败原因，不包含缓存已保存的声明。
     * @throws IOException 输入读取错误。
     * Callers: HostSymbols、scan、单元测试。
     */
    public static Properties scan(List<String> paths, String rules, ProgressListener progress) throws IOException {
        Map<String, List<String[]>> byShape = new HashMap<>();
        Map<String, Map<String, List<String[]>>> candidates = new TreeMap<>();
        for (String line : rules.split("\\R")) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] rule = line.split("\t");
            if (rule.length != 6) throw new IllegalArgumentException("无效 DEX 特征规则");
            bindings(rule[5]);
            candidates.computeIfAbsent(rule[0], key -> new TreeMap<>());
            byShape.computeIfAbsent(rule[2], key -> new ArrayList<>()).add(rule);
        }
        Map<String, List<String[]>> pending = new HashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        int dexCount = countDex(paths);
        int total = (dexCount * 2 + candidates.size()) * 1000;
        progress.onProgress(0, total, "准备查找目标方法");
        visit(paths, type -> {
            List<String[]> matching = byShape.get(shape(type));
            if (matching == null) return;
            String content = literals(type);
            for (String[] rule : matching) {
                if (content.equals(rule[3]) && matchesBindings(type, rule[5])) {
                    pending.computeIfAbsent(type.getType(), key -> new ArrayList<>()).add(rule);
                    dependencies.put(type.getType(), dependencies(type));
                    descriptions.put(type.getType(), describeMethods(type));
                }
            }
        }, progress, 0, total, "查找类与方法特征");
        Map<String, String> linked = linked(paths, dependencies, progress, dexCount * 1000, total);
        pending.forEach((type, matching) -> {
            for (String[] rule : matching) if (rule[4].equals(linked.get(type))) candidates.get(rule[0])
                .computeIfAbsent(type, key -> new ArrayList<>()).add(rule);
        });
        Properties result = new Properties();
        int[] completed = {dexCount * 2000};
        candidates.forEach((symbol, found) -> {
            if (found.size() != 1) result.setProperty("error." + symbol, "候选数量=" + found.size());
            else {
                String descriptor = found.keySet().iterator().next();
                List<String[]> matches = found.get(descriptor);
                Map<String, String> members = bindings(matches.get(0)[5]);
                boolean consistent = matches.stream().allMatch(rule -> members.equals(bindings(rule[5])));
                if (!consistent) result.setProperty("error." + symbol, "成员契约存在冲突");
                else {
                    String profile = matches.get(0)[1];
                    result.setProperty(symbol, descriptor.substring(1, descriptor.length() - 1).replace('/', '.') + "|" + profile);
                    members.forEach((role, member) -> result.setProperty("member." + symbol + "." + role, member));
                }
            }
            completed[0] += 1000;
            progress.onProgress(completed[0], total, "确认目标 " + symbol + "\n" +
                    (found.size() == 1 ? descriptions.get(found.keySet().iterator().next()) : "唯一候选未确定"));
        });
        return result;
    }

    /**
     * 解析业务角色与 DEX 成员描述的对应关系；重复角色和无效描述直接报错。
     * @param encoded 以竖线分隔的 role=descriptor，短横线表示没有成员绑定。
     * @return 有序角色映射，方法描述包含参数和返回类型，字段描述包含类型。
     * Callers: scan、matchesBindings。
     */
    private static Map<String, String> bindings(String encoded) {
        Map<String, String> result = new TreeMap<>();
        if (encoded.equals("-")) return result;
        for (String entry : encoded.split("\\|", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2 || !pair[0].matches("[A-Za-z][A-Za-z0-9]*") ||
                    !(pair[1].contains("(") || pair[1].contains(":")) ||
                    result.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("无效 DEX 成员绑定: " + entry);
        }
        return result;
    }

    /**
     * 校验规则引用的每个方法或字段在候选类内具有完整类型契约。
     * @param type 候选 DEX 类。
     * @param encoded 业务成员绑定，不允许缺少成员后继续发布该符号。
     * @return 所有绑定均存在时返回 true。
     * Callers: scan、离线规则生成器。
     */
    public static boolean matchesBindings(ClassDef type, String encoded) {
        Map<String, String> expected = bindings(encoded);
        if (expected.isEmpty()) return true;
        Set<String> actual = new HashSet<>();
        for (var field : type.getFields()) actual.add(field.getName() + ":" + normalize(field.getType()));
        for (Method method : type.getMethods()) actual.add(memberDescriptor(method));
        return actual.containsAll(expected.values());
    }

    /**
     * 构建方法的规范化 DEX 描述，保留名称、参数顺序和返回类型。
     * @param method 只读方法定义。
     * @return name(parameters)returnType 格式的描述。
     * Callers: matchesBindings、离线规则生成器。
     */
    public static String memberDescriptor(Method method) {
        StringBuilder value = new StringBuilder(method.getName()).append('(');
        method.getParameterTypes().forEach(value::append);
        return normalize(value.append(')').append(method.getReturnType()).toString());
    }

    /**
     * 仅读取ZIP目录，计算两轮遍历的DEX工作数量。
     * @param paths 宿主完整代码包集合。
     * @return 正整数DEX数量。
     * @throws IOException 包读取失败或没有DEX。
     * Callers: scan。
     */
    private static int countDex(List<String> paths) throws IOException {
        int count = 0;
        for (String path : paths) {
            try (ZipFile apk = new ZipFile(path)) {
                var entries = apk.entries();
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) count++;
                }
            }
        }
        if (count == 0) throw new IOException("宿主代码集合中没有 DEX");
        return count;
    }

    /**
     * 描述当前检查类的实际方法，最多显示八个名称，避免窗口被巨大类型填满。
     * @param type 当前DEX类。
     * @return 二进制类名及被检查的方法名称。
     * Callers: visit、scan。
     */
    private static String describeMethods(ClassDef type) {
        Set<String> methods = new LinkedHashSet<>();
        for (Method method : type.getMethods()) {
            if (!method.getName().startsWith("<")) methods.add(method.getName());
            if (methods.size() == 8) break;
        }
        String descriptor = type.getType();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.') + "\n方法：" +
                (methods.isEmpty() ? "仅构造或初始化方法" : String.join("、", methods));
    }

    /**
     * 计算 UTF-8 文本的 SHA-256；算法缺失属于运行环境错误，保留原因。
     * @param value 待计算文本。
     * @return 十六进制摘要。
     * Callers: shape、literals、identity、HostSymbols。
     */
    public static String digest(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 不可用", error); }
    }

    /** 编码字节。@param bytes 原始数据。@return 小写十六进制。Callers: digest、identity。 */
    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
}
