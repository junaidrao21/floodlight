package net.floodlightcontroller.dhcpserver;

import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.topology.NodePortTuple;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.forwarding.Forwarding;
import net.floodlightcontroller.packet.DHCP.DHCPOptionCode;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.DHCPOption;
import net.floodlightcontroller.dhcpserver.DHCPInstance.DHCPInstanceBuilder;

/**
 * SDN DHCP Server
 * @author Ryan Izard, rizard@g.clemson.edu
 * Modified by Qing Wang (qw@g.clemson.edu) on 6/28/17
 * 
 * The Floodlight Module implementing a DHCP DHCPServer.
 * This module uses {@code DHCPPool} to manage DHCP leases.
 * It intercepts any DHCP/BOOTP requests from connected hosts and
 * handles the replies. The configuration file:
 * 
 * 		floodlight/src/main/resources/floodlightdefault.properties
 * 
 * contains the DHCP options and parameters that can be set. To allow
 * all DHCP request messages to be sent to the controller (Floodlight),
 * the DHCPSwitchFlowSetter module (in this same package) and the
 * Forwarding module (loaded by default) should also be loaded in
 * Floodlight. When the first DHCP request is received on a particular
 * port of an OpenFlow switch, the request will by default be sent to
 * the control plane to the controller for processing. The DHCPServer
 * module will intercept the message before it makes it to the Forwarding
 * module and process the packet. Now, because we don't want to hog all
 * the DHCP messages (in case there is another module that is using them)
 * we forward the packets down to other modules using Command.CONTINUE.
 * As a side effect, the forwarding module will insert flows in the OF
 * switch for our DHCP traffic even though we've already processed it.
 * In order to allow all future DHCP messages from that same port to be
 * sent to the controller (and not follow the Forwarding module's flows),
 * we need to proactively insert flows for all DHCP client traffic on
 * UDP port 67 to the controller. These flows will allow all DHCP traffic
 * to be intercepted on that same port and sent to the DHCP server running
 * on the Floodlight controller.
 * 
 * Currently, this DHCP server only supports a single subnet; however,
 * work is ongoing to use connected OF switches and ports to allow
 * the user to configure multiple subnets. On a traditional DHCP server,
 * the machine is configured with different NICs, each with their own
 * statically-assigned IP address/subnet/mask. The DHCP server matches
 * the network information of each NIC with the DHCP server's configured
 * subnets and answers the requests accordingly. To mirror this behavior
 * on a OpenFlow network, we can differentiate between subnets based on a
 * device's attachment point. We can assign subnets for a device per
 * OpenFlow switch or per port per switch. This is the next step for
 * this implementations of a SDN DHCP server.
 *
 * I welcome any feedback or suggestions for improvement!
 * 
 * 
 */
public class DHCPServer implements IOFMessageListener, IFloodlightModule, IDHCPService {
	protected static Logger log;
	protected static IFloodlightProviderService floodlightProvider;
	protected static IOFSwitchService switchService;

	// The garbage collector service for the DHCP server
	// Handle expired leases by adding the IP back to the address pool
	private static ScheduledThreadPoolExecutor leasePoliceDispatcher;
	//private static ScheduledFuture<?> leasePoliceOfficer;
	private static Runnable leasePolicePatrol;

	// Contains the pool of IP addresses their bindings to MAC addresses
	// Tracks the lease status and duration of DHCP bindings
	private static Map<String, DHCPInstance> DHCPInstancesMap;
	private static volatile boolean enableDHCPService = false;

	private static long DHCP_SERVER_LEASE_POLICE_PATROL_PERIOD_SECONDS;
	/** END CONFIG FILE VARIABLES **/

	/**
	 * DHCP messages are either:
	 *		REQUEST (client --0x01--> server)
	 *		or REPLY (server --0x02--> client)
	 */
	/* Qing Wang enum Code here */
	public enum DHCPOpCode {
		OpCode_Request		((byte)1),
		OpCode_Reply		((byte)2);

		protected byte value;

		private DHCPOpCode(byte value) {
			this.value = value;
		}

		public byte getValue(){
			return value;
		}

	}

	/**
	 * DHCP REQUEST messages are either of type:
	 *		DISCOVER (0x01)
	 *		REQUEST (0x03)
	 * 		DECLINE (0x04)
	 *		RELEASE (0x07)
	 *		or INFORM (0x08)
	 * DHCP REPLY messages are either of type:
	 *		OFFER (0x02)
	 *		ACK (0x05)
	 *		or NACK (0x06)
	 **/
	public static byte[] DHCP_MSG_TYPE_DISCOVER = DHCPServerUtils.intToBytesSizeOne(1);
	public static byte[] DHCP_MSG_TYPE_OFFER = DHCPServerUtils.intToBytesSizeOne(2);
	public static byte[] DHCP_MSG_TYPE_REQUEST = DHCPServerUtils.intToBytesSizeOne(3);
	public static byte[] DHCP_MSG_TYPE_DECLINE = DHCPServerUtils.intToBytesSizeOne(4);
	public static byte[] DHCP_MSG_TYPE_ACK = DHCPServerUtils.intToBytesSizeOne(5);
	public static byte[] DHCP_MSG_TYPE_NACK = DHCPServerUtils.intToBytesSizeOne(6);
	public static byte[] DHCP_MSG_TYPE_RELEASE = DHCPServerUtils.intToBytesSizeOne(7);
	public static byte[] DHCP_MSG_TYPE_INFORM = DHCPServerUtils.intToBytesSizeOne(8);

	// Used for composing DHCP REPLY messages
	public static final IPv4Address BROADCAST_IP = IPv4Address.NO_MASK; /* no_mask is all 1's */
	public static final IPv4Address UNASSIGNED_IP = IPv4Address.FULL_MASK; /* full_mask is all 0's */
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = 
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		log = LoggerFactory.getLogger(DHCPServer.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);

		// TODO: add VLAN etc from configure file to DHCP Instance
		// Read our config options for the DHCP DHCPServer from floodlightdefault.properties file
		Map<String, String> configOptions = context.getConfigParams(this);
		DHCPInstanceBuilder instanceBuilder = DHCPInstance.createBuilder();
		try {
			instanceBuilder.setSubnetMask(IPv4Address.of(configOptions.get("subnet-mask")))
					.setStartIP(IPv4Address.of(configOptions.get("lower-ip-range")))
					.setEndIP(IPv4Address.of(configOptions.get("upper-ip-range")))
					.setBroadcastIP(IPv4Address.of(configOptions.get("broadcast-address")))
					.setRouterIP(IPv4Address.of(configOptions.get("router")))
					.setDomainName(configOptions.get("domain-name"))
					.setLeaseTimeSec(Integer.parseInt(configOptions.get("default-lease-time")))
					.setIPforwarding(Boolean.parseBoolean(configOptions.get("ip-forwarding")))
					.setServerMac(MacAddress.of(configOptions.get("controller-mac")))
					.setServerIP(IPv4Address.of(configOptions.get("controller-ip")));

			DHCP_SERVER_LEASE_POLICE_PATROL_PERIOD_SECONDS = Long.parseLong(configOptions.get("lease-gc-period"));

			// NetBios and other options can be added to this function here as needed in the future
		} catch(IllegalArgumentException ex) {
			log.error("Incorrect DHCP Server configuration options", ex);
			throw ex;
		} catch(NullPointerException ex) {
			log.error("Incorrect DHCP Server configuration options", ex);
			throw ex;
		}

		// Any addresses that need to be set as static/fixed can be permanently added to the pool with a set MAC
		String staticAddresses = configOptions.get("reserved-static-addresses");
		if (staticAddresses != null) {
			String[] macIpCouples = staticAddresses.split("\\s*;\\s*");
			int i;
			String[] macIpSplit;
			int ipPos, macPos;
			for (i = 0; i < macIpCouples.length; i++) {
				macIpSplit = macIpCouples[i].split("\\s*,\\s*");
				// Determine which element is the MAC and which is the IP
				// i.e. which order have they been typed in in the config file?
				if (macIpSplit[0].length() > macIpSplit[1].length()) {
					macPos = 0;
					ipPos = 1;
				} else {
					macPos = 1;
					ipPos = 0;
				}

				instanceBuilder.setStaticAddresses(MacAddress.of(macIpSplit[macPos]), IPv4Address.of(macIpSplit[ipPos]));
				log.info("Configured fixed address of " +
						IPv4Address.of(macIpSplit[ipPos]).toString() + " for device " +
						MacAddress.of(macIpSplit[macPos]).toString());

			}
		}

		// Separate the servers in the comma-delimited list
		// otherwise the client will get incorrect option information
		String dnses = configOptions.get("domain-name-servers");
		if(dnses != null){
			List<IPv4Address> dnsServerIPs = new ArrayList<IPv4Address>();
			for(String dnsServerIP : dnses.split("\\s*,\\s*")){
				dnsServerIPs.add(IPv4Address.of(dnsServerIP));
			}
			instanceBuilder.setDNSServers(dnsServerIPs);
		}

		String ntps = configOptions.get("ntp-servers");
		if(ntps != null){
			List<IPv4Address> ntpServerIPs = new ArrayList<IPv4Address>();
			for(String ntpServerIP : ntps.split("\\s*,\\s*")){
				ntpServerIPs.add(IPv4Address.of(ntpServerIP));
			}
			instanceBuilder.setNTPServers(ntpServerIPs);
		}

		DHCPInstance dhcpInstance = instanceBuilder.build();
		DHCPInstancesMap.put(dhcpInstance.getName(), dhcpInstance);

		// Monitor bindings for expired leases and clean them up
		leasePoliceDispatcher = new ScheduledThreadPoolExecutor(1);
		leasePolicePatrol = new DHCPLeasePolice();
		/*leasePoliceOfficer = */
		leasePoliceDispatcher.scheduleAtFixedRate(leasePolicePatrol, 10, 
				DHCP_SERVER_LEASE_POLICE_PATROL_PERIOD_SECONDS, TimeUnit.SECONDS);


		String enableDHCP = configOptions.get("enable");
		if(enableDHCP != null && !enableDHCP.isEmpty()) {
			enableDHCP();
		}else{
			disableDHCP();
		}

	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		return null;
	}

	@Override
	public String getName() {
		return DHCPServer.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// We will rely on forwarding to forward out any DHCP packets that are not
		// destined for our DHCP server. This is to allow an environment where
		// multiple DHCP servers operate cooperatively
		if (type == OFType.PACKET_IN && name.equals(Forwarding.class.getSimpleName())) {
			return true;
		} else {
			return false;
		}
	}


	/** (2) DHCP Offer Message
	 * -- UDP src port = 67
	 * -- UDP dst port = 68
	 * -- IP src addr = DHCP DHCPServer's IP
	 * -- IP dst addr = 255.255.255.255
	 * -- Opcode = 0x02
	 * -- XID = transactionX
	 * -- ciaddr = blank
	 * -- yiaddr = offer IP
	 * -- siaddr = DHCP DHCPServer IP
	 * -- giaddr = blank
	 * -- chaddr = Client's MAC
	 * -- Options:
	 * --	Option 53 = DHCP Offer
	 * --	Option 1 = SN Mask IP
	 * --	Option 3 = Router IP
	 * --	Option 51 = Lease time (s)
	 * --	Option 54 = DHCP DHCPServer IP
	 * --	Option 6 = DNS servers
	 **/
	public void sendDHCPOfferMsg(DHCPInstance instance, IOFSwitch sw, OFPort inPort, MacAddress chaddr, IPv4Address dstIPAddr,
								 IPv4Address yiaddr, IPv4Address giaddr, int xid, ArrayList<Byte> requestOrder) {

		OFPacketOut.Builder DHCPOfferPacket = sw.getOFFactory().buildPacketOut();
		DHCPOfferPacket.setBufferId(OFBufferId.NO_BUFFER);

		Ethernet ethDHCPOffer = new Ethernet();
		ethDHCPOffer.setSourceMACAddress(instance.getServerMac());
		ethDHCPOffer.setDestinationMACAddress(chaddr);
		ethDHCPOffer.setEtherType(EthType.IPv4);

		IPv4 ipv4DHCPOffer = new IPv4();
		if (dstIPAddr.equals(IPv4Address.NONE)) {
			ipv4DHCPOffer.setDestinationAddress(BROADCAST_IP);
		} else { // Client has IP and dhcpc must have crashed
			ipv4DHCPOffer.setDestinationAddress(dstIPAddr);
		}
		ipv4DHCPOffer.setSourceAddress(instance.getServerIP());
		ipv4DHCPOffer.setProtocol(IpProtocol.UDP);
		ipv4DHCPOffer.setTtl((byte) 64);

		UDP udpDHCPOffer = new UDP();
		udpDHCPOffer.setDestinationPort(UDP.DHCP_CLIENT_PORT);
		udpDHCPOffer.setSourcePort(UDP.DHCP_SERVER_PORT);

		DHCP dhcpDHCPOffer = setDHCPOfferMsg(instance, chaddr, yiaddr, giaddr, xid, requestOrder);

		ethDHCPOffer.setPayload(ipv4DHCPOffer.setPayload(udpDHCPOffer.setPayload(dhcpDHCPOffer)));
		DHCPOfferPacket.setInPort(OFPort.ANY);

		List<OFAction> actions = new ArrayList<OFAction>(1);
		actions.add(sw.getOFFactory().actions().output(inPort, 0xffFFffFF));
		DHCPOfferPacket.setActions(actions);

		DHCPOfferPacket.setData(ethDHCPOffer.serialize());

		log.debug("Sending DHCP OFFER");
		sw.write(DHCPOfferPacket.build());

	}

	private DHCP setDHCPOfferMsg(DHCPInstance instance, MacAddress chaddr, IPv4Address yiaddr, IPv4Address giaddr, int xid, ArrayList<Byte> requestOrder) {
		DHCP dhcpDHCPOffer = new DHCP();
		dhcpDHCPOffer.setOpCode(DHCPOpCode.OpCode_Reply.getValue());
		dhcpDHCPOffer.setHardwareType((byte) 1);
		dhcpDHCPOffer.setHardwareAddressLength((byte) 6);
		dhcpDHCPOffer.setHops((byte) 0);
		dhcpDHCPOffer.setTransactionId(xid);
		dhcpDHCPOffer.setSeconds((short) 0);
		dhcpDHCPOffer.setFlags((short) 0);
		dhcpDHCPOffer.setClientIPAddress(UNASSIGNED_IP);
		dhcpDHCPOffer.setYourIPAddress(yiaddr);
		dhcpDHCPOffer.setServerIPAddress(instance.getServerIP());
		dhcpDHCPOffer.setGatewayIPAddress(giaddr);
		dhcpDHCPOffer.setClientHardwareAddress(chaddr);

		List<DHCPOption> dhcpOfferOptions = new ArrayList<DHCPOption>();
		DHCPOption newOption;

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_MessageType.getValue());
		newOption.setData(DHCP_MSG_TYPE_OFFER);
		newOption.setLength((byte) 1);
		dhcpOfferOptions.add(newOption);

		for (Byte specificRequest : requestOrder) {
			newOption = new DHCPOption();
			if (specificRequest == DHCPOptionCode.OptionCode_SubnetMask.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_SubnetMask.getValue());
				newOption.setData(instance.getSubnetMask().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_Router.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_Router.getValue());
				newOption.setData(instance.getRouterIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DomainName.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DomainName.getValue());
				newOption.setData(instance.getDomainName().getBytes());
				newOption.setLength((byte) instance.getDomainName().getBytes().length);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DNS.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DNS.getValue());
				byte[] byteArray = DHCPServerUtils.IPv4ListToByteArr(instance.getDNSServers()); // Convert List<IPv4Address> to byte[]
				newOption.setData(byteArray);
				newOption.setLength((byte) byteArray.length);

			} else if (specificRequest == DHCPOptionCode.OptionCode_Broadcast_IP.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_Broadcast_IP.getValue());
				newOption.setData(instance.getBroadcastIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DHCPServerIp.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DHCPServerIp.getValue());
				newOption.setData(instance.getServerIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_LeaseTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_LeaseTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getLeaseTimeSec()));
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_NTP_IP.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_NTP_IP.getValue());
				byte[] byteArray = DHCPServerUtils.IPv4ListToByteArr(instance.getNtpServers()); // Convert List<IPv4Address> to byte[]
				newOption.setData(byteArray);
				newOption.setLength((byte) byteArray.length);

			} else if (specificRequest == DHCPOptionCode.OPtionCode_RebindingTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OPtionCode_RebindingTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getRebindTimeSec()));
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_RenewalTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_RenewalTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getRenewalTimeSec()));
				dhcpOfferOptions.add(newOption);

			} else if (specificRequest == DHCPOptionCode.OptionCode_IPForwarding.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_IPForwarding.getValue());
				newOption.setData(DHCPServerUtils.intToBytes( instance.getIpforwarding() ? 1 : 0 ));
				newOption.setLength((byte) 1);

			} else {
				log.debug("Setting specific request for OFFER failed");
			}

			dhcpOfferOptions.add(newOption);
		}

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_END.getValue());
		newOption.setLength((byte) 0);
		dhcpOfferOptions.add(newOption);

		dhcpDHCPOffer.setOptions(dhcpOfferOptions);
		return dhcpDHCPOffer;

	}


	/** (4) DHCP ACK Message
	 * -- UDP src port = 67
	 * -- UDP dst port = 68
	 * -- IP src addr = DHCP DHCPServer's IP
	 * -- IP dst addr = 255.255.255.255
	 * -- Opcode = 0x02
	 * -- XID = transactionX
	 * -- ciaddr = blank
	 * -- yiaddr = offer IP
	 * -- siaddr = DHCP DHCPServer IP
	 * -- giaddr = blank
	 * -- chaddr = Client's MAC
	 * -- Options:
	 * --	Option 53 = DHCP ACK
	 * --	Option 1 = SN Mask IP
	 * --	Option 3 = Router IP
	 * --	Option 51 = Lease time (s)
	 * --	Option 54 = DHCP DHCPServer IP
	 * --	Option 6 = DNS servers
	 **/
	public void sendDHCPAckMsg(DHCPInstance instance, IOFSwitch sw, OFPort inPort, MacAddress chaddr, IPv4Address dstIPAddr,
							   IPv4Address yiaddr, IPv4Address giaddr, int xid, ArrayList<Byte> requestOrder) {
		OFPacketOut.Builder DHCPACKPacket = sw.getOFFactory().buildPacketOut();
		DHCPACKPacket.setBufferId(OFBufferId.NO_BUFFER);

		Ethernet ethDHCPAck = new Ethernet();
		ethDHCPAck.setSourceMACAddress(instance.getServerMac());
		ethDHCPAck.setDestinationMACAddress(chaddr);
		ethDHCPAck.setEtherType(EthType.IPv4);

		IPv4 ipv4DHCPAck = new IPv4();
		if (dstIPAddr.equals(IPv4Address.NONE)) {
			ipv4DHCPAck.setDestinationAddress(BROADCAST_IP);
		} else { // Client has IP and dhclient must have crashed
			ipv4DHCPAck.setDestinationAddress(dstIPAddr);
		}
		ipv4DHCPAck.setSourceAddress(instance.getServerIP());
		ipv4DHCPAck.setProtocol(IpProtocol.UDP);
		ipv4DHCPAck.setTtl((byte) 64);

		UDP udpDHCPAck = new UDP();
		udpDHCPAck.setDestinationPort(UDP.DHCP_CLIENT_PORT);
		udpDHCPAck.setSourcePort(UDP.DHCP_SERVER_PORT);

		DHCP dhcpDHCPAck = setDHCPAckMsg(instance, chaddr, yiaddr, giaddr, xid, requestOrder);
		ethDHCPAck.setPayload(ipv4DHCPAck.setPayload(udpDHCPAck.setPayload(dhcpDHCPAck)));
		DHCPACKPacket.setInPort(OFPort.ANY);

		List<OFAction> actions = new ArrayList<OFAction>(1);
		actions.add(sw.getOFFactory().actions().output(inPort, 0xffFFffFF));
		DHCPACKPacket.setActions(actions);

		DHCPACKPacket.setData(ethDHCPAck.serialize());

		log.debug("Sending DHCP ACK");
		sw.write(DHCPACKPacket.build());
	}


	private DHCP setDHCPAckMsg(DHCPInstance instance, MacAddress chaddr, IPv4Address yiaddr, IPv4Address giaddr, int xid, ArrayList<Byte> requestOrder) {
		DHCP dhcpDHCPAck = new DHCP();
		dhcpDHCPAck.setOpCode(DHCPOpCode.OpCode_Reply.getValue());
		dhcpDHCPAck.setHardwareType((byte) 1);
		dhcpDHCPAck.setHardwareAddressLength((byte) 6);
		dhcpDHCPAck.setHops((byte) 0);
		dhcpDHCPAck.setTransactionId(xid);
		dhcpDHCPAck.setSeconds((short) 0);
		dhcpDHCPAck.setFlags((short) 0);
		dhcpDHCPAck.setClientIPAddress(UNASSIGNED_IP);
		dhcpDHCPAck.setYourIPAddress(yiaddr);
		dhcpDHCPAck.setServerIPAddress(instance.getServerIP());
		dhcpDHCPAck.setGatewayIPAddress(giaddr);
		dhcpDHCPAck.setClientHardwareAddress(chaddr);

		List<DHCPOption> dhcpAckOptions = new ArrayList<DHCPOption>();
		DHCPOption newOption;

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_MessageType.getValue());
		newOption.setData(DHCP_MSG_TYPE_ACK);
		newOption.setLength((byte) 1);
		dhcpAckOptions.add(newOption);

		for (Byte specificRequest : requestOrder) {

			newOption = new DHCPOption();
			if (specificRequest == DHCPOptionCode.OptionCode_SubnetMask.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_SubnetMask.getValue());
				newOption.setData(instance.getSubnetMask().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_Router.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_Router.getValue());
				newOption.setData(instance.getRouterIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DomainName.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DomainName.getValue());
				newOption.setData(instance.getDomainName().getBytes());
				newOption.setLength((byte) instance.getDomainName().getBytes().length);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DNS.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DNS.getValue());
				byte[] byteArray = DHCPServerUtils.IPv4ListToByteArr(instance.getDNSServers());		// Convert List<IPv4Address> to byte[]
				newOption.setData(byteArray);
				newOption.setLength((byte) byteArray.length);

			} else if (specificRequest == DHCPOptionCode.OptionCode_Broadcast_IP.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_Broadcast_IP.getValue());
				newOption.setData(instance.getBroadcastIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_DHCPServerIp.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_DHCPServerIp.getValue());
				newOption.setData(instance.getServerIP().getBytes());
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_LeaseTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_LeaseTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getLeaseTimeSec()));
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_NTP_IP.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_NTP_IP.getValue());
				byte[] byteArray = DHCPServerUtils.IPv4ListToByteArr(instance.getNtpServers());		// Convert List<IPv4Address> to byte[]
				newOption.setData(byteArray);
				newOption.setLength((byte) byteArray.length);

			} else if (specificRequest == DHCPOptionCode.OPtionCode_RebindingTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OPtionCode_RebindingTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getRebindTimeSec()));
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_RenewalTime.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_RenewalTime.getValue());
				newOption.setData(DHCPServerUtils.intToBytes(instance.getRenewalTimeSec()));
				newOption.setLength((byte) 4);

			} else if (specificRequest == DHCPOptionCode.OptionCode_IPForwarding.getValue()) {
				newOption.setCode(DHCPOptionCode.OptionCode_IPForwarding.getValue());
				newOption.setData(DHCPServerUtils.intToBytes( instance.getIpforwarding() ? 1 : 0 ));
				newOption.setLength((byte) 1);

			}else {
				log.debug("Setting specific request for ACK failed");
			}

			dhcpAckOptions.add(newOption);

		}

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_END.getValue());
		newOption.setLength((byte) 0);
		dhcpAckOptions.add(newOption);

		dhcpDHCPAck.setOptions(dhcpAckOptions);
		return dhcpDHCPAck;
	}

	public void sendDHCPNackMsg(DHCPInstance instance, IOFSwitch sw, OFPort inPort, MacAddress chaddr, IPv4Address giaddr, int xid) {
		OFPacketOut.Builder DHCPOfferPacket = sw.getOFFactory().buildPacketOut();
		DHCPOfferPacket.setBufferId(OFBufferId.NO_BUFFER);

		Ethernet ethDHCPOffer = new Ethernet();
		ethDHCPOffer.setSourceMACAddress(instance.getServerMac());
		ethDHCPOffer.setDestinationMACAddress(chaddr);
		ethDHCPOffer.setEtherType(EthType.IPv4);

		IPv4 ipv4DHCPOffer = new IPv4();
		ipv4DHCPOffer.setDestinationAddress(BROADCAST_IP);
		ipv4DHCPOffer.setSourceAddress(instance.getServerIP());
		ipv4DHCPOffer.setProtocol(IpProtocol.UDP);
		ipv4DHCPOffer.setTtl((byte) 64);

		UDP udpDHCPOffer = new UDP();
		udpDHCPOffer.setDestinationPort(UDP.DHCP_CLIENT_PORT);
		udpDHCPOffer.setSourcePort(UDP.DHCP_SERVER_PORT);

		DHCP dhcpNACK = getDHCPNAckMsg(instance, chaddr, giaddr, xid);
		ethDHCPOffer.setPayload(ipv4DHCPOffer.setPayload(udpDHCPOffer.setPayload(dhcpNACK)));
		DHCPOfferPacket.setInPort(OFPort.ANY);

		List<OFAction> actions = new ArrayList<OFAction>(1);
		actions.add(sw.getOFFactory().actions().output(inPort, 0xffFFffFF));
		DHCPOfferPacket.setActions(actions);
		DHCPOfferPacket.setData(ethDHCPOffer.serialize());

		log.info("Sending DHCP NACK");
		sw.write(DHCPOfferPacket.build());
	}

	private DHCP getDHCPNAckMsg(DHCPInstance instance, MacAddress chaddr, IPv4Address giaddr, int xid) {
		DHCP dhcpDHCPOffer = new DHCP();
		dhcpDHCPOffer.setOpCode(DHCPOpCode.OpCode_Reply.getValue());
		dhcpDHCPOffer.setHardwareType((byte) 1);
		dhcpDHCPOffer.setHardwareAddressLength((byte) 6);
		dhcpDHCPOffer.setHops((byte) 0);
		dhcpDHCPOffer.setTransactionId(xid);
		dhcpDHCPOffer.setSeconds((short) 0);
		dhcpDHCPOffer.setFlags((short) 0);
		dhcpDHCPOffer.setClientIPAddress(UNASSIGNED_IP);
		dhcpDHCPOffer.setYourIPAddress(UNASSIGNED_IP);
		dhcpDHCPOffer.setServerIPAddress(instance.getServerIP());
		dhcpDHCPOffer.setGatewayIPAddress(giaddr);
		dhcpDHCPOffer.setClientHardwareAddress(chaddr);

		List<DHCPOption> dhcpOfferOptions = new ArrayList<DHCPOption>();
		DHCPOption newOption;

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_MessageType.getValue());
		newOption.setData(DHCP_MSG_TYPE_NACK);
		newOption.setLength((byte) 1);
		dhcpOfferOptions.add(newOption);

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_DHCPServerIp.getValue());
		newOption.setData(instance.getServerIP().getBytes());
		newOption.setLength((byte) 4);
		dhcpOfferOptions.add(newOption);

		newOption = new DHCPOption();
		newOption.setCode(DHCPOptionCode.OptionCode_END.getValue());
		newOption.setLength((byte) 0);
		dhcpOfferOptions.add(newOption);

		dhcpDHCPOffer.setOptions(dhcpOfferOptions);
		return dhcpDHCPOffer;
	}

	public ArrayList<Byte> getRequestedParameters(DHCP DHCPPayload, boolean isInform) {
		ArrayList<Byte> requestOrder = new ArrayList<Byte>();
		byte[] requests = DHCPPayload.getOption(DHCPOptionCode.OptionCode_RequestedParameters).getData();
		boolean requestedLeaseTime = false;
		boolean requestedRebindTime = false;
		boolean requestedRenewTime = false;

		for (byte specificRequest : requests) {
			if (specificRequest == DHCPOptionCode.OptionCode_SubnetMask.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_SubnetMask.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_Router.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_Router.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_DomainName.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_DomainName.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_DNS.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_DNS.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_LeaseTime.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_LeaseTime.getValue());
				requestedLeaseTime = true;

			} else if (specificRequest == DHCPOptionCode.OptionCode_DHCPServerIp.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_DHCPServerIp.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_Broadcast_IP.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_Broadcast_IP.getValue());

			} else if (specificRequest == DHCPOptionCode.OptionCode_NTP_IP.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_NTP_IP.getValue());

			} else if (specificRequest == DHCPOptionCode.OPtionCode_RebindingTime.getValue()) {
				requestOrder.add(DHCPOptionCode.OPtionCode_RebindingTime.getValue());
				requestedRebindTime = true;

			} else if (specificRequest == DHCPOptionCode.OptionCode_RenewalTime.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_RenewalTime.getValue());
				requestedRenewTime = true;

			} else if (specificRequest == DHCPOptionCode.OptionCode_IPForwarding.getValue()) {
				requestOrder.add(DHCPOptionCode.OptionCode_IPForwarding.getValue());
				log.debug("requested IP FORWARDING");

			} else {
				log.debug("Requested option 0x" + Byte.toString(specificRequest) + " not available");

			}
		}
		
		// We need to add these in regardless if the request list includes them
		if (!isInform) {
			if (!requestedLeaseTime) {
				requestOrder.add(DHCPOptionCode.OptionCode_LeaseTime.getValue());
				log.debug("added option LEASE TIME");
			}
			if (!requestedRenewTime) {
				requestOrder.add(DHCPOptionCode.OptionCode_RenewalTime.getValue());
				log.debug("added option RENEWAL TIME");
			}
			if (!requestedRebindTime) {
				requestOrder.add(DHCPOptionCode.OPtionCode_RebindingTime.getValue());
				log.debug("added option REBIND TIME");
			}
		}
		return requestOrder;
	}

	@Override
	public void enableDHCP() {
		enableDHCPService = true;
	}

	@Override
	public void disableDHCP() {
		enableDHCPService = false;
	}

	@Override
	public boolean isDHCPEnabled() {
		return enableDHCPService;
	}

	@Override
	public boolean addInstance(DHCPInstance instance) {
		if (DHCPInstancesMap.containsKey(instance.getName())) {
			log.error("Failed to add DHCP instance{} : instance already existed", instance.getName());
			return false;
		}else {
			DHCPInstancesMap.put(instance.getName(), instance);
			return true;
		}
	}

	@Override
	public boolean deleteInstance(String name) {
		if (!DHCPInstancesMap.containsKey(name)) {
			log.error("Failed to delete DHCP instance {} : instance not exist", name);
			return false;
		} else {
			DHCPInstancesMap.remove(name);
			return true;
		}
	}

	@Override
	public Collection<DHCPInstance> getInstances() {
		return Collections.unmodifiableCollection(DHCPInstancesMap.values());
	}

	@Override
	public DHCPInstance getInstance(String name) {
		return DHCPInstancesMap.get(name);
	}

	@Override
	public DHCPInstance getInstance(IPv4Address ipAddr) {
		for (DHCPInstance instance : DHCPInstancesMap.values()) {
			if (instance.isIPv4BelongsInstance(ipAddr)) {
				return instance;
			}
		}
		return null;
	}

	@Override
	public DHCPInstance getInstance(NodePortTuple nptMember) {
		for (DHCPInstance instance : DHCPInstancesMap.values()) {
			if (instance.getNptMembers().contains(nptMember)) {
				return instance;
			}
		}
		return null;
	}

	@Override
	public DHCPInstance getInstance(VlanVid vidMember) {
		for (DHCPInstance instance : DHCPInstancesMap.values()) {
			if (instance.getVlanMembers().contains(vidMember)) {
				return instance;
			}
		}
		return null;
	}


	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

		/* Check DHCP Service enabled or not */
		if (!isDHCPEnabled()) { return Command.CONTINUE; }


		/* Get DHCP Instance based on Packet-In message */
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		OFPacketIn pi = (OFPacketIn) msg;

		OFPort inPort = pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT);

		VlanVid vlanVid = null;
		if (pi.getVersion().compareTo(OFVersion.OF_11) > 0 && pi.getMatch().get(MatchField.VLAN_VID) != null ) {
			vlanVid = pi.getMatch().get(MatchField.VLAN_VID).getVlanVid();	/* VLAN might have already been popped by switch */
		}
		if (vlanVid == null) {
			vlanVid = VlanVid.ofVlan(eth.getVlanID());						/* VLAN might still be in eth packet */
		}

		DHCPInstance dhcpInstance = getInstance(new NodePortTuple(sw.getId(), inPort));
		if (dhcpInstance == null) {
			log.debug("Could not locate DHCP instance for DPID {}, port {}. Check VLAN next", sw.getId(), inPort);
			dhcpInstance = getInstance(vlanVid);
		}
		if (dhcpInstance == null) {
			log.error("Could not locate DHCP instance for DPID {}, port {}, VLAN {}", new Object[] {sw.getId(), inPort, vlanVid});
		}
		if (!dhcpInstance.getDHCPPool().hasAvailableSpace()) {
			log.info("DHCP Pool is full! Consider increasing the pool size.");
			return Command.CONTINUE;
		}


		/* DHCP Instance begin to handle DHCP Messages */
		if (eth.getEtherType() == EthType.IPv4) { 				/* shallow compare is okay for EthType */
			log.debug("Got IPv4 Packet");

			IPv4 IPv4Payload = (IPv4) eth.getPayload();
			IPv4Address IPv4SrcAddr = IPv4Payload.getSourceAddress();
			if (IPv4Payload.getProtocol() == IpProtocol.UDP) {  /* shallow compare also okay for IpProtocol */
				log.debug("Got UDP Packet");
				UDP UDPPayload = (UDP) IPv4Payload.getPayload();

				if (isDHCPPacket(UDPPayload)) {					/* TransportPort must be deep though */
					log.debug("Got DHCP Packet");
					DHCP DHCPPayload = (DHCP) UDPPayload.getPayload();

					/* DHCP Header */
					int xid = 0;
					IPv4Address yiaddr = IPv4Address.NONE;
					IPv4Address giaddr = IPv4Address.NONE;
					IPv4Address desiredIPAddr = null;
					MacAddress chaddr = null;
					ArrayList<Byte> requestOrder;
					if (DHCPPayload.getOpCode() == DHCPOpCode.OpCode_Request.getValue()) {
						/**  * (1) DHCP Discover
						 * -- UDP src port = 68
						 * -- UDP dst port = 67
						 * -- IP src addr = 0.0.0.0
						 * -- IP dst addr = 255.255.255.255
						 * -- Opcode = 0x01
						 * -- XID = transactionX
						 * -- All addresses blank:
						 * --	ciaddr (client IP)
						 * --	yiaddr (your IP)
						 * --	siaddr (DHCPServer IP)
						 * --	giaddr (GW IP)
						 * -- chaddr = Client's MAC
						 * -- Options:
						 * --	Option 53 = DHCP Discover
						 * --	Option 50 = possible IP request
						 * --	Option 55 = parameter request list
						 * --		(1)  SN Mask
						 * --		(3)  Router
						 * --		(15) Domain Name
						 * --		(6)  DNS
						 **/
						if (Arrays.equals(DHCPPayload.getOption(DHCP.DHCPOptionCode.OptionCode_MessageType).getData(), DHCP_MSG_TYPE_DISCOVER)) {
							log.debug("DHCP DISCOVER Message Received");

							/* DHCP Header Info */
							xid = DHCPPayload.getTransactionId();
							yiaddr = DHCPPayload.getYourIPAddress();
							giaddr = DHCPPayload.getGatewayIPAddress();   // Will have GW IP if a relay agent was used
							chaddr = DHCPPayload.getClientHardwareAddress();

							List<DHCPOption> options = DHCPPayload.getOptions();
							requestOrder = new ArrayList<Byte>();
							for (DHCPOption option : options) {
								if (option.getCode() == DHCPOptionCode.OptionCode_RequestedIP.getValue()) {
									desiredIPAddr = IPv4Address.of(option.getData());
									log.debug("Got requested IP");
								} else if (option.getCode() == DHCPOptionCode.OptionCode_RequestedParameters.getValue()) {
									log.debug("Got requested param list");
									requestOrder = getRequestedParameters(DHCPPayload, false); 		
								}

							}

							// Process DISCOVER message and prepare an OFFER with minimum-hold lease
							// A HOLD lease should be a small amount of time sufficient for the client to respond
							// with a REQUEST, at which point the ACK will set the least time to the DEFAULT
							synchronized (dhcpInstance.getDHCPPool()) {
								if (!dhcpInstance.getDHCPPool().hasAvailableSpace()) {
									log.info("DHCP Pool is full! Consider increasing the pool size.");
									log.info("Device with MAC " + chaddr.toString() + " was not granted an IP lease");
									return Command.CONTINUE;
								}

								DHCPBinding lease = dhcpInstance.getDHCPPool().getSpecificLease(desiredIPAddr, chaddr);
								if (lease != null) {
									log.debug("Checking new lease with specific IP");
									dhcpInstance.getDHCPPool().setLeaseBinding(lease, chaddr, dhcpInstance.getLeaseTimeSec());
									yiaddr = lease.getIPv4Address();
									log.debug("Got new lease for " + yiaddr.toString());
								} else {
									log.debug("Checking new lease for any IP");
									lease = dhcpInstance.getDHCPPool().getAnyAvailableLease(chaddr);
									dhcpInstance.getDHCPPool().setLeaseBinding(lease, chaddr, dhcpInstance.getLeaseTimeSec());
									yiaddr = lease.getIPv4Address();
									log.debug("Got new lease for " + yiaddr.toString());
								}
							}

							sendDHCPOfferMsg(dhcpInstance, sw, inPort, chaddr, IPv4SrcAddr, yiaddr, giaddr, xid, requestOrder);

						} // END IF DISCOVER
						else if (Arrays.equals(DHCPPayload.getOption(DHCP.DHCPOptionCode.OptionCode_MessageType).getData(), DHCP_MSG_TYPE_REQUEST)) {
							log.debug(": DHCP REQUEST received");
							/** (3) DHCP Request
							 * -- UDP src port = 68
							 * -- UDP dst port = 67
							 * -- IP src addr = 0.0.0.0
							 * -- IP dst addr = 255.255.255.255
							 * -- Opcode = 0x01
							 * -- XID = transactionX
							 * -- ciaddr = blank
							 * -- yiaddr = blank
							 * -- siaddr = DHCP DHCPServer IP
							 * -- giaddr = GW IP
							 * -- chaddr = Client's MAC
							 * -- Options:
							 * --	Option 53 = DHCP Request
							 * --	Option 50 = IP requested (from offer)
							 * --	Option 54 = DHCP DHCPServer IP
							 **/
							xid = DHCPPayload.getTransactionId();
							yiaddr = DHCPPayload.getYourIPAddress();
							giaddr = DHCPPayload.getGatewayIPAddress();
							chaddr = DHCPPayload.getClientHardwareAddress();

							List<DHCPOption> options = DHCPPayload.getOptions();
							requestOrder = new ArrayList<Byte>();
							for (DHCPOption option : options) {
								if (option.getCode() == DHCPOptionCode.OptionCode_RequestedIP.getValue()) {
									desiredIPAddr = IPv4Address.of(option.getData());
									if (!desiredIPAddr.equals(dhcpInstance.getDHCPPool().getDHCPbindingFromMAC(chaddr).getIPv4Address())) {
										// This client wants a different IP than what we have on file, so cancel its HOLD lease now (if we have one)
										dhcpInstance.getDHCPPool().cancelLeaseOfMAC(chaddr);
										return Command.CONTINUE;
									}
								} else if (option.getCode() == DHCPOptionCode.OptionCode_DHCPServerIp.getValue()) {
									if (!IPv4Address.of(option.getData()).equals(dhcpInstance.getServerIP())) {
										// We're not the DHCPServer the client wants to use, so cancel its HOLD lease now and ignore the client
										dhcpInstance.getDHCPPool().cancelLeaseOfMAC(chaddr);
										return Command.CONTINUE;
									}
								} else if (option.getCode() == DHCPOptionCode.OptionCode_RequestedParameters.getValue()) {
									requestOrder = getRequestedParameters(DHCPPayload, false);
								}

							}

							// Process REQUEST message and prepare an ACK with default lease time
							// This extends the hold lease time to that of a normal lease
							boolean sendACK = true;
							synchronized (dhcpInstance.getDHCPPool()) {
								if (!dhcpInstance.getDHCPPool().hasAvailableSpace()) {
									log.info("DHCP Pool is full! Consider increasing the pool size.");
									log.info("Device with MAC " + chaddr.toString() + " was not granted an IP lease");
									return Command.CONTINUE;
								}

								DHCPBinding lease;

								// Get any binding, in use now or not
								if (desiredIPAddr != null) {
									lease = dhcpInstance.getDHCPPool().getDHCPbindingFromIPv4(desiredIPAddr);
								} else {
									lease = dhcpInstance.getDHCPPool().getAnyAvailableLease(chaddr);
								}

								// This IP is not in our allocation range
								if (lease == null) {
									log.info("The IP " + desiredIPAddr.toString() + " is not in the range " 
											+ dhcpInstance.getStartIPAddress().toString() + " to " + dhcpInstance.getEndIPAddress().toString());
									log.info("Device with MAC " + chaddr.toString() + " was not granted an IP lease");
									sendACK = false;
									// Determine if the IP in the binding we just retrieved is okay to allocate to the MAC requesting it
								} else if (!lease.getMACAddress().equals(chaddr) && lease.isLeaseAvailable()) {
									log.debug("Tried to REQUEST an IP that is currently assigned to another MAC");
									log.debug("Device with MAC " + chaddr.toString() + " was not granted an IP lease");
									sendACK = false;
									// Check if we want to renew the MAC's current lease
								} else if (lease.getMACAddress().equals(chaddr) && lease.isLeaseAvailable()) {
									log.debug("Renewing lease for MAC " + chaddr.toString());
									dhcpInstance.getDHCPPool().renewLease(lease.getIPv4Address(), dhcpInstance.getLeaseTimeSec());
									yiaddr = lease.getIPv4Address();
									log.debug("Finalized renewed lease for " + yiaddr.toString());
									// Check if we want to create a new lease for the MAC
								} else if (!lease.isLeaseAvailable()){
									log.debug("Assigning new lease for MAC " + chaddr.toString());
									dhcpInstance.getDHCPPool().setLeaseBinding(lease, chaddr, dhcpInstance.getLeaseTimeSec());
									yiaddr = lease.getIPv4Address();
									log.debug("Finalized new lease for " + yiaddr.toString());
								} else {
									log.debug("Don't know how we got here");
									return Command.CONTINUE;
								}

							}

							if (sendACK) {
								sendDHCPAckMsg(dhcpInstance, sw, inPort, chaddr, IPv4SrcAddr, yiaddr, giaddr, xid, requestOrder);
							} else {
								sendDHCPNackMsg(dhcpInstance, sw, inPort, chaddr, giaddr, xid);
							}

						} // END IF REQUEST
						else if (Arrays.equals(DHCPPayload.getOption(DHCP.DHCPOptionCode.OptionCode_MessageType).getData(), DHCP_MSG_TYPE_RELEASE)) {
							if (DHCPPayload.getServerIPAddress() != dhcpInstance.getServerIP()) {
								log.info("DHCP RELEASE message not for our DHCP server");
								// Send the packet out the port it would normally go out via the Forwarding module
								// Execution jumps to return Command.CONTINUE at end of receive()
							} else {
								log.debug("Got DHCP RELEASE. Cancelling remaining time on DHCP lease");
								synchronized(dhcpInstance.getDHCPPool()) {
									if (dhcpInstance.getDHCPPool().cancelLeaseOfMAC(DHCPPayload.getClientHardwareAddress())) {
										log.info("Cancelled DHCP lease of " + DHCPPayload.getClientHardwareAddress().toString());
										log.info("IP " + dhcpInstance.getDHCPPool().getDHCPbindingFromMAC(DHCPPayload.getClientHardwareAddress()).getIPv4Address().toString()
												+ " is now available in the DHCP address pool");
									} else {
										log.debug("Lease of " + DHCPPayload.getClientHardwareAddress().toString()
												+ " was already inactive");
									}
								}

							}
						} // END IF RELEASE
						else if (Arrays.equals(DHCPPayload.getOption(DHCP.DHCPOptionCode.OptionCode_MessageType).getData(), DHCP_MSG_TYPE_DECLINE)) {
							log.debug("Got DHCP DECLINE. Cancelling HOLD time on DHCP lease");
							synchronized(dhcpInstance.getDHCPPool()) {
								if (dhcpInstance.getDHCPPool().cancelLeaseOfMAC(DHCPPayload.getClientHardwareAddress())) {
									log.info("Cancelled DHCP lease of " + DHCPPayload.getClientHardwareAddress().toString());
									log.info("IP " + dhcpInstance.getDHCPPool().getDHCPbindingFromMAC(DHCPPayload.getClientHardwareAddress()).getIPv4Address().toString()
											+ " is now available in the DHCP address pool");
								} else {
									log.info("HOLD Lease of " + DHCPPayload.getClientHardwareAddress().toString()
											+ " has already expired");
								}
							}

						} // END IF DECLINE
						else if (Arrays.equals(DHCPPayload.getOption(DHCP.DHCPOptionCode.OptionCode_MessageType).getData(), DHCP_MSG_TYPE_INFORM)) {
							log.debug("Got DHCP INFORM. Retreiving requested parameters from message");

							xid = DHCPPayload.getTransactionId();
							yiaddr = DHCPPayload.getYourIPAddress();
							giaddr = DHCPPayload.getGatewayIPAddress();
							chaddr = DHCPPayload.getClientHardwareAddress();

							// Get the requests from the INFORM message. True for inform -- we don't want to include lease information
							requestOrder = getRequestedParameters(DHCPPayload, true);
							
							// Process INFORM message and send an ACK with requested information
							sendDHCPAckMsg(dhcpInstance, sw, inPort, chaddr, IPv4SrcAddr, yiaddr, giaddr, xid, requestOrder);

						} // END IF INFORM

					} // END IF DHCP OPCODE REQUEST 
					else if (DHCPPayload.getOpCode() == DHCPOpCode.OpCode_Reply.getValue()) {
						// Do nothing right now. The DHCP DHCPServer isn't supposed to receive replies but ISSUE them instead
						log.debug("Got an OFFER/ACK (REPLY)...this shouldn't happen unless there's another DHCP Server somewhere");

					} else {
						log.debug("Got DHCP packet, but not a known DHCP packet opcode");

					}
				} // END IF DHCP packet

			} // END IF UDP packet

		} // END IF IPv4 packet

		return Command.CONTINUE;

	} // END of receive(pkt)

	private boolean isDHCPPacket(UDP UDPPayload) {
		return (UDPPayload.getDestinationPort().equals(UDP.DHCP_SERVER_PORT)
                || UDPPayload.getDestinationPort().equals(UDP.DHCP_CLIENT_PORT))
                && (UDPPayload.getSourcePort().equals(UDP.DHCP_SERVER_PORT)
                || UDPPayload.getSourcePort().equals(UDP.DHCP_CLIENT_PORT));

	}

	/**
	 * DHCPLeasePolice is a simple class that is instantiated and invoked
	 * as a runnable thread. The objective is to clean up the expired DHCP
	 * leases on a set time interval. Most DHCP leases are hours in length,
	 * so the granularity of our check can be on the order of minutes (IMHO).
	 * The period of the check for expired leases, in seconds, is specified
	 * in the configuration file:
	 * 
	 * 		floodlight/src/main/resources/floodlightdefault.properties
	 * 
	 * as option:
	 * 
	 * 		net.floodlightcontroller.dhcpserver.DHCPServer.lease-gc-period = <seconds>
	 * 
	 * where gc stands for "garbage collection".
	 * 
	 * @author Ryan Izard, rizard@g.clemson.edu
	 *
	 */
	class DHCPLeasePolice implements Runnable {
		@Override
		public void run() {
			log.info("Cleaning any expired DHCP leases...");
			ArrayList<DHCPBinding> newAvailableBindings;
			for(DHCPInstance instance : DHCPInstancesMap.values()){
				synchronized(instance.getDHCPPool()) {
					// Loop through lease pool and check all leases to see if they are expired
					// If a lease is expired, then clean it up and make the binding available
					newAvailableBindings = instance.getDHCPPool().cleanExpiredLeases();
				}
				for (DHCPBinding binding : newAvailableBindings) {
					log.info("MAC " + binding.getMACAddress().toString() + " has expired");
					log.info("Lease now available for IP " + binding.getIPv4Address().toString());
				}

			}

		}
	} // END DHCPLeasePolice Class

} // END DHCPServer Class

