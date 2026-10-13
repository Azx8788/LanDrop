# R8 压缩规则
# 保留 Manifest 中引用的 Activity（R8 自动处理，这里显式声明保险）
-keep class com.example.landrop.MainActivity { *; }
-keep class com.example.landrop.TransferServer { *; }

# 不警告缺失的可选类
-dontwarn
