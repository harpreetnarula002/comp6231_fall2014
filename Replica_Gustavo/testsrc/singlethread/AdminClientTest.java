package singlethread;
import idl.Library;

import java.util.Map;

import org.omg.CORBA.UserException;

import ui.UserUI;
import client.AdminClient;
import client.POALoader;
import client.StudentClient;
import entities.constants.PropertiesEnum;


public class AdminClientTest {
	
	private AdminClient loadAdminClient(final String institution) throws UserException {
		Library poa = loadPoa(institution);
		return new AdminClient("admin", "admin", institution, poa);
	}
	
	private StudentClient loadStudentClient(final String institution) throws UserException {
		Library poa = loadPoa(institution);
		StudentClient sc = new StudentClient("act", "pw", institution, poa);
		sc.createAccount("first", "last", "email", "phone");
		return sc;
	}
	
	protected Library loadPoa(String institution) throws UserException {
		Map<String, String> properties = UserUI.loadProperties();
		String port = properties.get(PropertiesEnum.ORB_INITIAL_PORT.val());
		String host = properties.get(PropertiesEnum.ORB_INITIAL_HOST.val());
		
		return POALoader.load(port, host, institution);
	}
	
	private void assertMessage(String expected, String received, String method) {
		if (expected.equals(received)) {
			System.out.println(method + ": SUCCESS");
		} else {
			System.err.println(method + ": FAIL");
			System.out.println("\texpected: " + expected);
			System.out.println("\treceived: " + received);
		}
	}
	
	public void setDurationFail(AdminClient ac) {
		String msg = ac.setDuration("inexistent", "inexistent", -1);
		String expected = "Unable to find student with username = [inexistent].";
		assertMessage(expected, msg, "setDurationFail");
	}
	
	public void setDurationSuccess(AdminClient ac, StudentClient sc) {
		sc.reserveBook("book1", "author1");
		String msg = ac.setDuration("act", "book1", -1);
		String expected = "Duration of reservation for user [act] on book [book1] updated for [-1] days.";
		assertMessage(expected, msg, "setDurationSuccess");
	}
	
	public void getNonRetunersSuccess(AdminClient ac, StudentClient sc) {
		sc.reserveBook("book2", "author2");
		ac.setDuration("act", "book2", -3);
		
		String msg = ac.getNonRetuners(0);
		String expected = "\nconcordia: first last phone"
				+ "\n......"
				+ "\nwebster: "
				+ "\n......                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               "
				+ "\nvanier: "
				+ "\n......                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                ";
		assertMessage(expected, msg, "getNonRetunersSuccess");
	}

	public static void main(String[] args) throws UserException {
		System.out.println(">>> Before starting this test, please start (or restart) 'server.StartServer' for libraries 'concordia', 'vanier', and 'webster' <<<");
		AdminClientTest test = new AdminClientTest();
		String institution = "concordia";
		AdminClient ac = test.loadAdminClient(institution);
		StudentClient sc = test.loadStudentClient(institution);
		test.setDurationFail(ac);
		test.setDurationSuccess(ac, sc);
		test.getNonRetunersSuccess(ac, sc);
		System.out.println("Shut down library server when you are done.");
	}

}
