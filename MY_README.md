# joern-parse

```joern-cli/src/main/scala/io/joern/joerncli/JoernParse.scala```

1. 导入依赖

2. 定义JoernParse对象（参数及main函数）

    1）参数：cpg默认路径，cpg generator
    
    2）main函数：运行run函数，并根据run函数的结果输出msg / err。

3. 定义命令行解析器

4. run函数

5. **generateCPG函数**
    
    1）调用cpgGeneratorForLanguage生成CPG

6. 其他函数

8. 配置选项ParserConfig

## Questions

1. 什么是overlay选项？

# joern-slice

```joern-cli/src/main/scala/io/joern/joerncli/JoernSlice.scala```

调用

```dataflowengineoss/src/main/scala/io/joern/dataflowengineoss/slicing/DataFlowSlicing.scala```

但是使用joern-parse或者joern-dump时并不调用该文件。
可能是由于joern-slice基于joern-parse解析好的文件，进行数据流查询。
