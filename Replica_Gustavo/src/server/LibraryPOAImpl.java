package server;

import idl.LibraryPOA;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import service.LibraryService;
import service.LibraryServiceImpl;
import entities.Book;
import entities.Student;
import entities.constants.PropertiesEnum;
import entities.constants.UdpEnum;

public class LibraryPOAImpl extends LibraryPOA {
	private final Logger LOGGER;

	private final String name;
	private final LibraryService service;
	private final UDPServer udpServer;
	
	private final Map<String, String> properties;

	public LibraryPOAImpl(final String institution, final Map<String, String> properties) {
		this.name = institution;
		System.setProperty("obj.log","./library_"+name+".log");

		this.properties = properties;
		final int udpPort = new Integer(getLibraryProperty(PropertiesEnum.LIBRARY_UDP_PORT));
		final String studentsCsv = getLibraryProperty(PropertiesEnum.LIBRARY_STUDENTS_FILE);
		final String booksCsv = getLibraryProperty(PropertiesEnum.LIBRARY_BOOKS_FILE);
		
		this.service = new LibraryServiceImpl(studentsCsv, booksCsv, name);
		
		LOGGER = Logger.getLogger(LibraryPOAImpl.class);
		
		// start the udp server
		this.udpServer = new UDPServer(institution, udpPort, this);
		Thread t = new Thread(this.udpServer);
		t.start();
	}
	
	public String getProperty(PropertiesEnum property) {
		return this.properties.get(property.val());
	}
	
	public String getLibraryProperty(PropertiesEnum property) {
		return getLibraryProperty(name, property);
	}
	
	public String getLibraryProperty(String library,PropertiesEnum property) {
		return this.properties.get(library + "." + property.val());
	}
	
	private String[] loadLibraryNames() {
		String libraries = getProperty(PropertiesEnum.LIBRARIES);
		if (libraries != null && libraries.length() > 0) {
			return libraries.split(",");
		} else {
			throw new IllegalArgumentException("Unable to load property 'libraries'. Please check your config.proprties file.");
		}
	}
	
	private Map<Integer, String> getPortLibraryMap() {
		Map<Integer, String> map = new HashMap<Integer, String>();
		String[] libraries = loadLibraryNames();
		for (String library : libraries) {
			int port = new Integer(getLibraryProperty(library, PropertiesEnum.LIBRARY_UDP_PORT));
			map.put(port, library);
		}
		return map;
	}
	
	private String buildUdpMsg(UdpEnum method, String... params ) {
		String split = getProperty(PropertiesEnum.UDP_MSG_SPLIT);
		String clientMsg = method.name() + split + name;
		for (String s : params) {
			clientMsg += split + s;
		}
		return clientMsg;
	}
	
	public String processUdpClientMsg(final String clientMsg) {
		String message = null;
		
		if (clientMsg != null && clientMsg.length() > 0) {
			String[] msgArray = clientMsg.trim().split(getProperty(PropertiesEnum.UDP_MSG_SPLIT));
			
			if (msgArray != null && msgArray.length > 2) {
				String methodName = msgArray[0];
				String educationalInstitution = msgArray[1];
				String[] params = Arrays.copyOfRange(msgArray, 2, msgArray.length);
				
				if (methodName.equals(UdpEnum.RESERVE_INTER_LIBRARY.name())) {
					message = reserveFromExternal(params[0], params[1], params[2]);
				} else if (methodName.equals(UdpEnum.GET_NON_RETURNERS.name())) {
					int numDays = Integer.parseInt(params[0]);
					message = getNonRetuners(null, null, educationalInstitution, numDays);
				}
			}
		}
		
		return message;
	}

	@Override
	public String createAccount(String firstName, String lastName, String emailAddress, String phoneNumber, String username,
			String password, String educationalInstitution) {
		Student student = new Student(username, password, educationalInstitution, firstName, lastName, emailAddress, phoneNumber);
		String message = service.createAccount(student);
		LOGGER.info("createAccount result: " + message);

		return message;
	}

	@Override
	public String reserveBook(String username, String password, String bookName, String authorName) {
		Student student = new Student(username, password, this.name);
		Book book = new Book(bookName, authorName);

		String message = service.reserveBook(student, book);
		LOGGER.info("reserveBook result: " + message);

		return message;
	}

	@Override
	public String reserveInterLibrary(String username, String password, String bookName, String authorName) {
		Student student = new Student(username, password, this.name);
		Book book = new Book(bookName, authorName);
		boolean reserved = false;

		String message = service.reserveBook(student, book);
		if (bookInexistentLocally(message)) {
			String host = getProperty(PropertiesEnum.UDP_INITIAL_HOST);
			Map<Integer, String> portLibMap = getPortLibraryMap();

			for (Entry<Integer, String> entry : portLibMap.entrySet()) {
				// only process other libraries (not current)
				if (!this.name.equals(entry.getValue())) {
					String externalLib = entry.getValue();
					int port = entry.getKey();
					// do pre-reservation
					String preReservationMsg = service.addExternalReservationToLocalUser(username, book, externalLib);
					LOGGER.debug("Pre-reservation of book [" + book + "] for user [" + username + "] on library [" + externalLib + "]");
					
					// check if preReservationMsg was successful
					if (hasReserved(preReservationMsg)) {
						LOGGER.debug("Calling reserveInterLibrary() from client: " + name + " on " + host + ":" + port);
						String clientMsg = buildUdpMsg(UdpEnum.RESERVE_INTER_LIBRARY, username, bookName, authorName);
						final String libraryMsg = UDPClient.sendUdpRequest(host, port, clientMsg);
						if (hasReserved(libraryMsg)) {
							// successful reservation:
							message = libraryMsg.trim();
							reserved = true;
							break;
						} else {
							// undo pre-reservation
							preReservationMsg = service.removeFailedExternalReservation(username, book);
							LOGGER.debug("Reverting pre-reservation of book [" + book + "] for user [" + username + "] on library [" + externalLib + "]");
						}
					}
				}
			}
			if (!reserved) {
				message = "Unable to reserve inter-library";
			}
		}
		
		LOGGER.info("reserveInterLibrary result: " + message);		
		
		return message;
	}
	
	private boolean bookInexistentLocally(final String message) {
		return (message != null) && 
				((message.contains(LibraryService.INEXISTENT_BOOK_INIT)) || (message.contains(LibraryService.NO_COPIES_INIT)));
	}
	
	private boolean hasReserved(final String libraryMsg) {
		boolean wasSuccess = false;
		if ((libraryMsg != null) && (libraryMsg.contains(LibraryService.BOOK_RESERVED_INIT))) {
			wasSuccess = true;
		}
		return wasSuccess;
	}
	
	/**
	 * Process reservation request from other library. 
	 * @param username
	 * @param bookName
	 * @param authorName
	 * @return Message with the status of the reservation attempt.
	 */
	private String reserveFromExternal(String username, String bookName, String authorName) {
		Book book = new Book(bookName, authorName);
		return service.reserveBookExternal(username, book);
	}

	@Override
	public String getNonRetuners(String adminUsername, String adminPassword, String educationalInstitution, int numDays) {
		StringBuffer sb = new StringBuffer();
		sb.append("\n" + name + ": ");
		String result = service.getNonRetuners(numDays);
		sb.append(result);
		if (result.length() == 0) {
			sb.append("\n......");
		} else {
			sb.append("......");
		}
		
		String host = getProperty(PropertiesEnum.UDP_INITIAL_HOST);

		// if request was to this institution, call other servers
		if (this.name.equals(educationalInstitution)) {
			Map<Integer, String> portLibMap = getPortLibraryMap();
			for (Entry<Integer, String> entry : portLibMap.entrySet()) {
				// only process other libraries (not current)
				if (!this.name.equals(entry.getValue())) {
					int port = entry.getKey();
					LOGGER.debug("Calling sendUdpRequest() from client: " + name + " on " + host + ":" + port);
					String clientMsg = buildUdpMsg(UdpEnum.GET_NON_RETURNERS, Integer.toString(numDays));
					sb.append(UDPClient.sendUdpRequest(host, port, clientMsg));
				}
			}
		}
		LOGGER.info("Got non-returners for: adminUsername = " + adminUsername + "\teducationalInstitution = "
				+ educationalInstitution + "\tnumDays = " + numDays);
		return sb.toString();
	}

	@Override
	public String setDuration(String username, String bookName, int num_of_days) {
		String message = service.setDuration(username, bookName, num_of_days);
		LOGGER.info("setDuration result: " + message);
		return message;
	}

}
