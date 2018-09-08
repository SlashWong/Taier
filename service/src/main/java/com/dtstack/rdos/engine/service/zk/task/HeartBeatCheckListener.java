package com.dtstack.rdos.engine.service.zk.task;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.dtstack.rdos.engine.execution.base.CustomThreadFactory;
import com.dtstack.rdos.engine.service.node.MasterNode;
import com.dtstack.rdos.engine.service.zk.data.BrokerHeartNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dtstack.rdos.commom.exception.ExceptionUtil;
import com.dtstack.rdos.common.util.PublicUtil;
import com.dtstack.rdos.engine.service.db.dao.RdosNodeMachineDAO;
import com.dtstack.rdos.engine.service.enums.RdosNodeMachineType;
import com.dtstack.rdos.engine.service.zk.ZkDistributed;
import com.google.common.collect.Maps;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2018/9/8
 */
public class HeartBeatCheckListener implements Runnable{

	private static final Logger logger = LoggerFactory.getLogger(HeartBeatCheckListener.class);

	private final static int CHECK_INTERVAL = 2;

	public final static long STOP_HEALTH_CHECK_SEQ = -1;
	private final static int TIMEOUT_COUNT = 30;
	private MasterListener masterListener;

	private ZkDistributed zkDistributed = ZkDistributed.getZkDistributed();
	private MasterNode masterNode = MasterNode.getInstance();
	private RdosNodeMachineDAO rdosNodeMachineDAO = new RdosNodeMachineDAO();

	private int logOutput = 0;

	public HeartBeatCheckListener(MasterListener masterListener){
		this.masterListener = masterListener;
        ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory("HeartBeatCheckListener"));
        scheduledService.scheduleWithFixedDelay(
                this,
                0,
                CHECK_INTERVAL,
                TimeUnit.SECONDS);
	}

	private Map<String, BrokerNodeCount> brokerNodeCounts =  Maps.newHashMap();

	@Override
	public void run() {
        try {
            if(this.masterListener.isMaster()){
                logOutput++;
                healthCheck();
                if(PublicUtil.count(logOutput, 5)){logger.warn("HeartBeatCheckListener start check again...");}
            }
        } catch (Throwable e) {
            logger.error(ExceptionUtil.getErrorMessage(e));
        }
	}

    /**
     * 节点未正常重启、宕机都由 master 的健康检查机制来做任务恢复
	 * healthCheck 后的 broker-heart-seq = -1
     */
	private void healthCheck(){
		List<String> childrens = this.zkDistributed.getBrokersChildren();
		if(childrens!=null){
			for(String node:childrens){
				BrokerHeartNode brokerNode = this.zkDistributed.getBrokerHeartNode(node);
				boolean ignore = brokerNode == null || brokerNode.getAlive() && STOP_HEALTH_CHECK_SEQ == brokerNode.getSeq();
				if(ignore){
					continue;
				}
				BrokerNodeCount brokerNodeCount = brokerNodeCounts.computeIfAbsent(node, k->{
					this.rdosNodeMachineDAO.ableMachineNode(node, RdosNodeMachineType.SLAVE.getType());
					return new BrokerNodeCount(brokerNode);
				});
				//是否假死
				if (brokerNode.getAlive()){
					if(brokerNodeCount.getHeartSeq() == brokerNode.getSeq().longValue()){
						brokerNodeCount.increment();
					}else{
						brokerNodeCount.reset();
					}
				}else{
					//对失去心跳的节点，可能在重启，进行计数（fixme 正常退出时在hook中可以进行标记，区分宕机和正常退出，做快速恢复策略）
					brokerNodeCount.increment();
					this.rdosNodeMachineDAO.disableMachineNode(node, RdosNodeMachineType.SLAVE.getType());
				}
				if(brokerNodeCount.getCount() > TIMEOUT_COUNT){
					//先置为 false
					this.zkDistributed.disableBrokerHeartNode(node,false);
					//再进行容灾，容灾时还需要再判断一下是否alive，node可能已经恢复
					this.masterNode.dataMigration(node);
					this.zkDistributed.removeBrokerQueueNode(node);
					this.brokerNodeCounts.remove(node);
				}else{
					brokerNodeCount.setBrokerHeartNode(brokerNode);
				}
			}
		}
	}

	private static class BrokerNodeCount{
		private AtomicLong count;
		private BrokerHeartNode brokerHeartNode;
		public BrokerNodeCount(BrokerHeartNode brokerHeartNode){
			this.count = new AtomicLong(0L);
			this.brokerHeartNode  = brokerHeartNode;
		}
		public long getCount() {
			return count.get();
		}
		public void increment() {
			count.incrementAndGet();
		}
		public void reset() {
			count.set(0L);
		}
		public long getHeartSeq() {
			return brokerHeartNode.getSeq().longValue();
		}
		public void setBrokerHeartNode(BrokerHeartNode brokerHeartNode) {
			this.brokerHeartNode = brokerHeartNode;
		}
	}
}
