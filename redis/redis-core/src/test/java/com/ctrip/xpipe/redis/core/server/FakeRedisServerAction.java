package com.ctrip.xpipe.redis.core.server;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ctrip.xpipe.netty.ByteBufUtils;
import com.ctrip.xpipe.redis.core.protocal.cmd.AbstractPsync;
import com.ctrip.xpipe.redis.core.protocal.protocal.BulkStringParser;

import io.netty.buffer.ByteBuf;

/**
 * @author wenchao.meng
 *
 * Aug 26, 2016
 */
public class FakeRedisServerAction extends AbstractRedisAction{
		
	private BlockingQueue<String> writeCommands = new LinkedBlockingQueue<>();
	
	private FakeRedisServer fakeRedisServer;
	public FakeRedisServerAction(FakeRedisServer fakeRedisServer) {
		this.fakeRedisServer = fakeRedisServer;
	}

	@Override
	protected String getInfo() {
		return String.format("port:%d", fakeRedisServer.getPort());
	}

	@Override
	protected void handlePsync(OutputStream ous, String line) throws IOException, InterruptedException {
		
		logger.info("[handlePsync]{}", line);
		String []sp = line.split("\\s+");
		if(sp[1].equalsIgnoreCase("?")){
			handleFullSync(ous);
			return;
		}
		
		long offset = Long.parseLong(sp[2]);
		handlePartialSync(ous, offset);
	}

	private void handlePartialSync(OutputStream ous, long offset) throws IOException, InterruptedException {
		
		logger.info("[handlePartialSync]");
		int index = (int) (offset - fakeRedisServer.getRdbOffset() -1);
		if(index<0){
			logger.info("[indx < 0][full sync]");
			handleFullSync(ous);
			return;
		}

		String info = String.format("+%s\r\n", AbstractPsync.PARTIAL_SYNC);
		ous.write(info.getBytes());

		fakeRedisServer.addCommandsListener(this);

		writeCommands(ous);
		
	}

	private void writeCommands(OutputStream ous) throws IOException, InterruptedException {
		while(true){
			
			String command = writeCommands.take();
			String []sps = split(command);
			for(String sp : sps){
				logger.debug("[writeCommands]{}, {}", socket, sp);
				ous.write(sp.getBytes());
				ous.flush();
				TimeUnit.MILLISECONDS.sleep(fakeRedisServer.getSendBatchIntervalMilli());
			}
		}
	}

	private String[] split(String command) {
		
		int splitLen = fakeRedisServer.getSendBatchSize();
		
		int count = command.length()/splitLen;
		if(command.length()%splitLen >0){
			count++;
		}
		String []result = new String[count];
		int index =0;
		for(int i=0;i<count;i++){
			result[i] = command.substring(index, Math.min(index+splitLen, command.length()));
			index += splitLen;
		}
		return result;
	}

	private void handleFullSync(final OutputStream ous) throws IOException, InterruptedException {
		
		logger.info("[handleFullSync]{}", getSocket());
		fakeRedisServer.reGenerateRdb();
		fakeRedisServer.addCommandsListener(this);

		try {
			Thread.sleep(fakeRedisServer.getSleepBeforeSendFullSyncInfo());
		} catch (InterruptedException e) {
		}

		String info = String.format("+%s %s %d\r\n", AbstractPsync.FULL_SYNC, fakeRedisServer.getRunId(), fakeRedisServer.getRdbOffset());
		ous.write(info.getBytes());
		ous.flush();
		
		ScheduledFuture<?> future = null;
		if(fakeRedisServer.isSendLFBeforeSendRdb()){
			future = sendLfToSlave(ous);
		}
		
		try {
			Thread.sleep(fakeRedisServer.getSleepBeforeSendRdb());
		} catch (InterruptedException e) {
		}
		
		if(future != null){
			future.cancel(true);
		}
		
		BulkStringParser bulkStringParser = new BulkStringParser(fakeRedisServer.getRdbContent());
		ByteBuf byteBuf = bulkStringParser.format();
		byte []rdb = ByteBufUtils.readToBytes(byteBuf);
		if(logger.isDebugEnabled()){
			logger.debug("[handleFullSync]{}, {}", getSocket(), new String(rdb));
		}
		ous.write(rdb);
		writeCommands(ous);
		
	}

	private ScheduledFuture<?> sendLfToSlave(final OutputStream ous) {

		return scheduled.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				
				try {
					ous.write("\n".getBytes());
				} catch (IOException e) {
					logger.error("[run]" + this, e);
				}
			}
		}, 0, 1, TimeUnit.SECONDS);
	}

	public void addCommands(String commands){
		writeCommands.offer(commands);
	}
	
	@Override
	public void setDead() {
		logger.info("[setDead]{}", getSocket());
		fakeRedisServer.removeListener(this);
	}
	
	
	public static void main(String []argc){
		
//		System.out.println(StringUtil.join(",", new  FakeRedisServerAction(null).split("0123456789")));
		
		
	}
}
