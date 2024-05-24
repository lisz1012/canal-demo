package com.houge.canal;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.common.utils.AddressUtils;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import redis.clients.jedis.Jedis;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/*
 * 注意⚠️ 下面的 "canal" 和 "redis-1" 都是主机名，请用当前看环境中的主机名或者IP代替
 * 程序启动后就定期地从canal中拉取数据，然后转换成一个JSON字符串，并以它为value，user_id为key，通过
 * Jedis客户端写入Redis
 * 注意⚠️ 最通用的写法是将app server作为Kafka的消费者，而不是直接连接canal，否则可能有丢消息的风险！
 * 注意⚠️ 如何让消息尽快同步到下游DB或者消费是关键.
 */
public class AppMain {
    public static void main(String[] args) {
        // 创建链接
        CanalConnector connector = CanalConnectors.newSingleConnector(new InetSocketAddress("canal",
                11111), "example", "", "");
        int batchSize = 1000;
        try (Jedis jedis = new Jedis("redis-1", 6379)) {
            connector.connect();
            connector.subscribe(".*\\..*");
            connector.rollback();
            while (true) {
                Message message = connector.getWithoutAck(batchSize); // 获取指定数量的数据.
                long batchId = message.getId();
                try {
                    int size = message.getEntries().size();
                    if (batchId == -1 || size == 0) {
                        Thread.sleep(1000);
                    } else {
                        processEntries(message.getEntries(), jedis);
                    }
                    connector.ack(batchId); // 提交确认
                } catch (Throwable t) {
                    connector.rollback(batchId); // 处理失败, 回滚数据
                }
            }
        } finally {
            connector.disconnect();
        }
    }

    private static void processEntries(List<CanalEntry.Entry> entrys, Jedis jedis) {
        for (CanalEntry.Entry entry : entrys) {
            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN ||
                    entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                continue;
            }

            CanalEntry.RowChange rowChange;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                throw new RuntimeException("ERROR ## parser of eromanga-event has an error , data:" + entry.toString(),
                        e);
            }

            CanalEntry.EventType eventType = rowChange.getEventType();
            System.out.println(String.format("binlog[%s:%s] , name[%s,%s] , eventType : %s",
                    entry.getHeader().getLogfileName(), entry.getHeader().getLogfileOffset(),
                    entry.getHeader().getSchemaName(), entry.getHeader().getTableName(),
                    eventType));

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                if (eventType == CanalEntry.EventType.DELETE) { // 删除
                    printColumn(rowData.getBeforeColumnsList());
                    jedis.del(row2Key("id", rowData.getBeforeColumnsList()));
                } else if (eventType == CanalEntry.EventType.INSERT) { // 插入
                    printColumn(rowData.getAfterColumnsList());
                    jedis.set(row2Key("id", rowData.getAfterColumnsList()),
                              row2Value(rowData.getAfterColumnsList()));
                } else { // 更新
                    System.out.println("before");
                    printColumn(rowData.getBeforeColumnsList());
                    System.out.println("after");
                    printColumn(rowData.getAfterColumnsList());
                    jedis.set(row2Key("id", rowData.getAfterColumnsList()),
                              row2Value(rowData.getAfterColumnsList()));

                }
            }
        }
    }

    private static void printColumn(List<CanalEntry.Column> columns) {
        for (CanalEntry.Column column : columns) {
            System.out.println(column.getName() + " : " + column.getValue() + "    update=" + column.getUpdated());
        }
    }

    private static byte [] row2Key(String keyColumn, List<CanalEntry.Column> columns) {
        return columns.stream().filter(c -> keyColumn.equals(c.getName()))
                .map(c -> c.getValue().getBytes(StandardCharsets.UTF_8)).findAny()
                .orElseThrow(() -> new RuntimeException("Key column not found in the row!"));
    }
    private static byte [] row2Value(List<CanalEntry.Column> columns) {
        return new JSONObject(
                columns.stream().collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue))
        ).toString().getBytes(StandardCharsets.UTF_8);
    }
}
