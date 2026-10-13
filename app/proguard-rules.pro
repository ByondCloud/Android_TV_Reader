# ══════════════════════════════════════════════════════════════════
#  R8 / ProGuard 规则
# ══════════════════════════════════════════════════════════════════
#
# 【这个文件是干什么的】
# R8 会在 release 构建时做两件事：
#   1. 压缩（shrink）—— 删掉没有被用到的类和方法
#   2. 优化（optimize）—— 内联、去虚化、常量传播等
#
# 这两件事都建立在"它知道哪些代码真的会被调用"之上。
# 而**反射**、**序列化**、**JNI** 这类用法它看不出来 ——
# R8 会误以为某段代码"没人用"就给删掉，运行时才崩溃。
#
# 这个文件就是用来告诉 R8："这些别动"。
#
# 【好消息：我们这个项目几乎不需要写规则】
# AndroidX / Compose / Kotlin 的库都自带了 consumer rules
# （打包在 aar 里，会自动被合并进来），常见的坑已经由官方处理了。
# 我们的 domain 层是纯 Kotlin 数据类，全程没有反射，所以很安全。
#
# 【什么时候需要在这里加规则】
#   · 用了反射（Class.forName、反射调用方法/字段）
#   · 用了 Gson / Moshi / kotlinx.serialization（视配置而定）
#   · 有 JNI 调用的 native 方法
#   · 崩在 ClassNotFoundException / NoSuchMethodError 上时
# ══════════════════════════════════════════════════════════════════


# ── 保留行号信息，让线上崩溃栈可读 ──────────────────────────────
# R8 默认会把行号也去掉，崩溃日志里就只剩方法名，很难定位。
# 这两行让栈里保留源文件名和行号。
-keepattributes SourceFile,LineNumberTable

# 把源文件名重命名成 "SourceFile"，既保留可读性又不暴露原始文件名
-renamesourcefileattribute SourceFile


# ── Kotlin 的一些元数据要保留 ────────────────────────────────────
# Kotlin 的反射、协程的挂起函数恢复、以及某些库的默认参数处理
# 会用到这些注解信息。
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,InnerClasses,Signature


# ── 数据类（本项目当前用不上，但留个模板）────────────────────────
# 如果将来引入了需要反射的数据类（比如配合 Gson 做 JSON），
# 就把类名填进来，保留它的构造函数和字段。
#
# -keep class com.cloud.androidtvreader.domain.model.** { *; }
#
# 【为什么现在注释掉】
# 保留整个 model 包会阻止 R8 优化，白白损失性能。
# 只有在真的崩了、并且确认是反射引起的时候才打开。
