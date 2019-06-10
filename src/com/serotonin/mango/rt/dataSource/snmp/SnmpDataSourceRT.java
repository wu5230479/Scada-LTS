/*
    Mango - Open Source M2M - http://mango.serotoninsoftware.com
    Copyright (C) 2006-2011 Serotonin Software Technologies Inc.
    @author Matthew Lohbihler
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.mango.rt.dataSource.snmp;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.serotonin.mango.Common;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.mango.rt.dataImage.DataPointRT;
import com.serotonin.mango.rt.dataImage.PointValueTime;
import com.serotonin.mango.rt.dataImage.SetPointSource;
import com.serotonin.mango.rt.dataSource.DataSourceRT;
import com.serotonin.mango.rt.dataSource.PollingDataSource;
import com.serotonin.mango.vo.dataSource.snmp.SnmpDataSourceVO;
import com.serotonin.mango.vo.dataSource.snmp.SnmpPointLocatorVO;
import com.serotonin.web.i18n.LocalizableMessage;

/**
 * @author Matthew Lohbihler
 * 
 */
public class SnmpDataSourceRT extends PollingDataSource {

	enum MessageType{
		oidError,
		unknownOid,
		undefined

	}
	class SnmpResponses {
		PDU request = null;
		PDU response = null;
		long responseTime;

		SnmpResponses(){}

		public void setRequest(PDU request){

			this.request = request;
		}
		public PDU getRequest(){

			return this.request;
		}
		public PDU getResponseByGet() {

			try {
				startTime();
				response = getResponse(true);
				finishTime();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return response;
		}
		public PDU getResponseBySet(){
			PDU response = null;
			try {
				startTime();
				response = getResponse(false);
				finishTime();
			} catch (Exception ex) {
				// TODO add error handling
				response = null;
			}
			return response;
		}
		private PDU getResponse(boolean setOrGet) throws IOException {

			return setOrGet?snmp.get(request, target).getResponse():snmp.set(request, target).getResponse();

		}
		private void startTime(){

			responseTime = System.currentTimeMillis();

		}
		private void finishTime(){

			responseTime = System.currentTimeMillis() - responseTime;
			log.debug("Snmp request/response time: " + responseTime);

		}
	}

	public static final int DATA_SOURCE_EXCEPTION_EVENT = 1;
	public static final int PDU_EXCEPTION_EVENT = 2;

	private final Log log = LogFactory.getLog(SnmpDataSourceRT.class);

	private final SnmpDataSourceVO vo;
	private final Version version;
	private String address;
	private Target target;
	private Snmp snmp;
	private int counterEmptyResponsesOrResponsesWithError;
	private boolean deviceDidNotRespondDespiteTheCounterOfRetries = Boolean.FALSE;
	private SnmpResponses snmpRequests;

	public Snmp getSnmp() {
		return snmp;
	}

	public SnmpDataSourceRT(SnmpDataSourceVO vo) {
		super(vo);
		setPollingPeriod(vo.getUpdatePeriodType(), vo.getUpdatePeriods(), false);
		this.vo = vo;
		version = Version.getVersion(vo.getSnmpVersion(), vo.getCommunity(),
				vo.getSecurityName(), vo.getAuthProtocol(),
				vo.getAuthPassphrase(), vo.getPrivProtocol(),
				vo.getPrivPassphrase(), vo.getEngineId(),
				vo.getContextEngineId(), vo.getContextName());
		snmpRequests = new SnmpResponses();
	}

	@Override
	public void setPointValue(DataPointRT dataPoint, PointValueTime valueTime,
			SetPointSource source) {
		PDU request = version.createPDU();
		SnmpPointLocatorRT locator = dataPoint.getPointLocator();
		request.add(new VariableBinding(getOid(dataPoint), locator
				.valueToVariable(valueTime.getValue())));
		snmpRequests.setRequest(request);
		PDU response = snmpRequests.getResponseBySet();

		LocalizableMessage message = validatePdu(response);
		if (message != null)
			raiseEvent(PDU_EXCEPTION_EVENT, valueTime.getTime(), false, message);
		else
			dataPoint.setPointValue(valueTime, source);
	}
	public void setDeviceDidNotRespondDespiteTheCounterOfRetries(boolean deviceDidNotRespondDespiteTheCounterOfRetries) {
		this.deviceDidNotRespondDespiteTheCounterOfRetries = deviceDidNotRespondDespiteTheCounterOfRetries;
		log.info("Device did not respond despite the counter of retries.");
	}
	public void createSnmpAndStartListening(){
		try {
			initializeComponents();

		} catch (Exception e) {
			log.info(e.getMessage());
		}
	}
	public boolean isSnmpConnectionIsAlive(){
		if (target.getRetries() == counterEmptyResponsesOrResponsesWithError) {
			setDeviceDidNotRespondDespiteTheCounterOfRetries(Boolean.TRUE);
			return Boolean.FALSE;
		}
		else
			return Boolean.TRUE;
	}

	@Override
	protected void doPoll(long time) {
		try {
			doPollImpl(time);
		} catch (Exception e) {
			raiseEvent(PDU_EXCEPTION_EVENT, time, true,
					DataSourceRT.getExceptionMessage(e));
		}
	}

	private void doPollImpl(long time) throws IOException {
		snmpRequests.setRequest(version.createPDU());
		PDU request = snmpRequests.getRequest();
		PDU response = null;
		VariableBinding vb;

		// Make a copy of the oids so that we can check if we got everything we
		// asked for, and
		// only what we asked for.
		List<DataPointRT> requestPoints = new ArrayList<DataPointRT>();

		// Add OID to send in the PDU.
		for (DataPointRT dp : dataPoints) {
			if (!getLocatorVO(dp).isTrapOnly()) {
				request.add(new VariableBinding(getOid(dp)));
				requestPoints.add(dp);
			}
		}
		if(time!=TIME_JUNIT.TIME_EXISTS_ONLY_DURING_JUNIT.getTime()) {
			if (request.getVariableBindings().size() == 0) {
				// Nothing to send, so don't bother.
				returnToNormal(PDU_EXCEPTION_EVENT, time);
				return;
			}
		}

		// Get the response.
		snmpRequests.setRequest(version.createPDU());
		response = snmpRequests.getResponseByGet();

		// Take a look at the response.
		LocalizableMessage message = validateResponseAndValidateStateOfConnection(response);
		if(time==-1){
			if(!isSnmpConnectionIsAlive())
				snmp.close();
		}
		else {
			if(!isSnmpConnectionIsAlive()) {
				Common.ctx.getRuntimeManager().stopDataSourceAndDontJoinTermination(vo.getId());
			}
			else
			if(message != null)
				raiseEvent(PDU_EXCEPTION_EVENT, time, true, message);
			else {
				MessageType messageType = MessageType.undefined;
				boolean error = false;

				DataPointRT dp;
				for (int i = 0; i < response.size(); i++) {
					vb = response.get(i);
					// Find the command for this binding.
					dp = setDataPoint(vb,requestPoints);
					if (dp != null) {
						requestPoints.remove(dp);

						// Check if this is an error.
						if (vb.getVariable().isException()) {
							messageType = MessageType.oidError;
						} else {
							updatePoint(dp, vb.getVariable(), time);
						}
					} else {
						messageType = MessageType.unknownOid;
					}
					if (messageType != MessageType.undefined) {
						error = true;
						logEventsDependsOnMessageType(messageType, vb, dp, time);
						messageType = MessageType.undefined;
					}
				}
				for (DataPointRT requestPoint : requestPoints) {
					error = true;
					raiseEvent(PDU_EXCEPTION_EVENT, time, true,
							new LocalizableMessage("event.snmp.noBinding",
									getOid(requestPoint), address));
				}
				if (!error)
					// Deactivate any existing event.
					returnToNormal(PDU_EXCEPTION_EVENT, time);

			}
		}
	}
	private DataPointRT setDataPoint(VariableBinding vb, List<DataPointRT> requestPoints){
		for (DataPointRT requestPoint : requestPoints) {
			if (getOid(requestPoint).equals(vb.getOid())) {
				return  requestPoint;
			}
		}
		return null;
	}
	public LocalizableMessage validateResponseAndValidateStateOfConnection(PDU response){
		LocalizableMessage message = validatePdu(response);

		increaseCounterIfErrorExistOrNoResponseAppear(response);

		return message;
	}

	/**
	 * if messageType is different that value 0 or 1
	 * then LocalizableMessage is created and is gived to raiseEvent method.
	 * Then result of this is true,otherwise (messageType == -1) is false.
	 *
	 * @param messageType
	 * @param vb
	 * @param dp
	 * @param time
	 * @return boolean
	 */
	private boolean logEventsDependsOnMessageType(MessageType messageType, VariableBinding vb, DataPointRT dp, long time){
		LocalizableMessage message=null;
		switch(messageType) {
			case oidError:
				message=new LocalizableMessage(
						"event.snmp.oidError",
						address, getOid(dp), vb.getVariable());
				break;
			case unknownOid:
				message=new LocalizableMessage(
						"event.snmp.unknownOid",
						vb.getOid(), address);
				break;
		}

		if(messageType!=MessageType.undefined)
			raiseEvent(PDU_EXCEPTION_EVENT,time,true,message);

		return messageType!=MessageType.undefined;
	}
	private LocalizableMessage validatePdu(PDU pdu) {
		if (pdu == null)
			return new LocalizableMessage("event.snmp.noResponse");

		if (pdu.getErrorIndex() != 0)
			return new LocalizableMessage("event.snmp.pduOidError", pdu.get(
					pdu.getErrorIndex() - 1).getOid(), pdu.getErrorStatusText());

		if (pdu.getErrorStatus() != 0)
			return new LocalizableMessage("event.snmp.pduErrorStatus",
					pdu.getErrorStatus(), pdu.getErrorStatusText());

		return null;
	}

	private void increaseCounterIfErrorExistOrNoResponseAppear(PDU pdu) {
		if ((pdu == null) || (pdu.getErrorIndex() != 0) || (pdu.getErrorStatus() != 0)) {
			++counterEmptyResponsesOrResponsesWithError;
			log.info("Counter Empty Responses Or Responses With Error: "+counterEmptyResponsesOrResponsesWithError);
		}
	}



	private OID getOid(DataPointRT dp) {
		return ((SnmpPointLocatorRT) dp.getPointLocator()).getOid();
	}

	private SnmpPointLocatorVO getLocatorVO(DataPointRT dp) {
		return ((SnmpPointLocatorRT) dp.getPointLocator()).getVO();
	}

	int getTrapPort() {
		return vo.getTrapPort();
	}

	String getLocalAddress() {
		return vo.getLocalAddress();
	}

	String getAddress() {
		return address;
	}

	void receivedTrap(PDU trap) {
		long time = System.currentTimeMillis();
		VariableBinding vb;

		// Take a look at the response.
		LocalizableMessage message = validatePdu(trap);
		if (message != null)
			raiseEvent(PDU_EXCEPTION_EVENT, time, false, message);
		else {
			synchronized (pointListChangeLock) {
				updateChangedPoints();

				for (int i = 0; i < trap.size(); i++) {
					vb = trap.get(i);
					boolean found = false;

					// Find the command for this binding.
					for (DataPointRT dp : dataPoints) {
						if (getOid(dp).equals(vb.getOid())) {
							updatePoint(dp, vb.getVariable(), time);
							found = true;
						}
					}

					if (!found)
						log.warn("Trap not handled: " + vb);
				}
			}
		}
	}

	private void updatePoint(DataPointRT dp, Variable variable, long time) {
		SnmpPointLocatorRT locator = dp.getPointLocator();
		dp.updatePointValue(new PointValueTime(locator
				.variableToValue(variable), time));
	}

	//
	// /
	// / Lifecycle
	// /
	//
	@Override
	public void initialize() {
		try {
			initializeComponents();
			counterEmptyResponsesOrResponsesWithError=0;
			log.info("Counter Empty Responses Or Responses With Error is set 0.");

			SnmpTrapRouter.addDataSource(this);

			// Deactivate any existing event.
			returnToNormal(DATA_SOURCE_EXCEPTION_EVENT,
					System.currentTimeMillis());
		} catch (Exception e) {
			raiseEvent(DATA_SOURCE_EXCEPTION_EVENT, System.currentTimeMillis(),
					true, DataSourceRT.getExceptionMessage(e));
			log.debug("Error while initializing data source", e);
			return;
		}

		super.initialize();
	}
	private void initializeComponents() throws IOException {

		address = InetAddress.getByName(vo.getHost()).getHostAddress();
		target = version.getTarget(vo.getHost(), vo.getPort(),
				vo.getRetries(), vo.getTimeout());
		snmp = new Snmp(new DefaultUdpTransportMapping());
		snmp.listen();

	}
	@Override
	public void terminate() {
		super.terminate();

		SnmpTrapRouter.removeDataSource(this);

		try {
			if (snmp != null)
				snmp.close();
		} catch (IOException e) {
			throw new ShouldNeverHappenException(e);
		}
	}
}
