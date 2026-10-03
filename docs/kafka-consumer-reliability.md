# 单实例轻量可靠消费

## 配置与分区

文件处理 Topic 与 DLT 默认均为 3 partitions；DLT 声明至少 3 分区，并匹配配置中更多的主分区。
同一 Consumer Group 的 concurrency 默认为 3。
PROCESS_CONTENT（含重复发送与不同 generation）和旧 REINDEX 发送均使用 `fileMd5` 作为 Kafka key。
正常运行时，同一内容的消息进入同一 partition 并串行处理，不同分区并行处理。

配置入口：

- `SPRING_KAFKA_TOPIC_PARTITIONS=3`
- `SPRING_KAFKA_CONSUMER_CONCURRENCY=3`
- `SPRING_KAFKA_MAX_POLL_INTERVAL_MS=1800000`（30 分钟）

自建 ConsumerFactory 显式采用 `enable.auto.commit=false`、`auto.offset.reset=earliest`、
`isolation.level=read_committed`、`max.poll.records=1`。容器使用 `AckMode.RECORD`，
未引入 Consumer Kafka 事务：Listener 成功返回后提交本条 offset；异常交给错误处理器。

## Checkpoint 与恢复

- MERGED：下载、解析，成功提交 PARSED，再执行向量化并提交 INDEXED。
- PARSED：跳过下载和解析，直接执行向量化。
- INDEXED / FAILED：当前代次直接跳过。
- generation 不匹配：跳过旧任务，不更新当前代次。

业务异常只记录 `processingError`，不改变 MERGED / PARSED checkpoint，并向 Kafka 抛出。
成功提交 PARSED / INDEXED 时清理错误。普通可重试异常保持 `FixedBackOff(3000, 4)`：
首次加 4 次重试；标准框架不可重试异常（包括反序列化异常）直接走恢复流程。

恢复器先确认 DLT Kafka 事务发布成功，再用独立 MySQL 事务将合法任务的当前代次标记 FAILED。
DLT 发布失败不标记 FAILED；数据库终止状态更新失败也继续向错误处理器抛出。
无法解析或内容身份非法的消息只投递 DLT，不修改业务状态。
ErrorHandlingDeserializer 使用标准 JsonDeserializer 委托；Producer 按类型对正常任务使用 JSON、
对 DLT 原始错误字节使用 ByteArraySerializer，避免把错误字节编码为 JSON/base64。

## 现有 Broker Topic 的上线操作

本次代码修改没有连接或修改 Broker。Spring Boot 提供 KafkaAdmin，正常权限与连接下，
NewTopic Bean 可创建 Topic，并增加已有 Topic 的分区数；初始化失败并不代表应用一定停止。
请实际检查 `file-processing-topic1` 与 `file-processing-dlt`：

```sh
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic file-processing-topic1
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic file-processing-dlt
```

如果任一 Topic 少于 3 partitions，管理员执行（命令路径和 Broker 地址按部署调整）：

```sh
kafka-topics.sh --bootstrap-server localhost:9092 --alter --topic file-processing-topic1 --partitions 3
kafka-topics.sh --bootstrap-server localhost:9092 --alter --topic file-processing-dlt --partitions 3
```

分区数只能增加，不能减少；如果主 Topic 已多于 3 分区，DLT 应至少匹配主 Topic 的实际分区数。
同步检查部署环境的分区与 concurrency 覆盖配置。旧 docker-compose 初始化脚本创建的是
`file-processing`（不同名称），不能把它当成实际 Listener 使用的 Topic。

切换 key 或增加分区会改变消息分区映射。上线前停止新投递、排空旧主 Topic 消息，
再扩分区/切换配置，避免旧 key/旧映射的积压消息与新消息并行处理相同内容。

## 接受的可靠性边界

没有 processingOwner/token/lease、Inbox、数据库抢占、分布式锁或 Exactly Once。
极端 rebalance 下旧业务线程仍运行、新 Consumer 已接管时，可能短时间重复执行。
超过 max.poll.interval.ms 的任务仍可能触发此情况，30 分钟不是执行时间的无限保证。

阶段业务副作用与 checkpoint 提交之间仍有崩溃窗口；checkpoint 是已提交阶段，
不能保证一次失败/崩溃尝试的所有部分副作用都回滚。DLT 发布、FAILED 更新、源 offset
也不是同一事务，失败窗口可能重复发布 DLT。重启/异常变化可能重置框架重试计数。

FAILED 是当前 generation 的终态，后续重新处理需要创建新 generation 的 PROCESS_CONTENT。
本轮没有新增人工 generation 管理接口或 DLT 自动重放；旧 REINDEX 接口尚未完成代次迁移。
