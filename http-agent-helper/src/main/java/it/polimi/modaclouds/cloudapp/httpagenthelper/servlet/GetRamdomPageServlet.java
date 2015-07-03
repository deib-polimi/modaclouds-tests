package it.polimi.modaclouds.cloudapp.httpagenthelper.servlet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet implementation class GetPageServlet
 */
@WebServlet("/getRandomPage")
public class GetRamdomPageServlet extends HttpServlet {

	private static final long serialVersionUID = -3866752859346288461L;
	
	private static final Logger logger = LoggerFactory.getLogger(GetRamdomPageServlet.class);
	
	private static List<File> files = null;
	
	private static void initArray() throws FileNotFoundException {
		if (files != null)
			return;
		
		files = new ArrayList<File>();
		
		File root = Paths.get(GetPageServlet.BASE_PATH).toFile();
		
		if (!root.exists() || !root.isDirectory()) {
			files = null;
			throw new FileNotFoundException();
		}
		
		for (File f : root.listFiles())
			addFileToArray(f);
		
		logger.info("The files array has now {} elements.", files.size());
	}
	
	private static void addFileToArray(File file) {
		if (!file.isDirectory())
			files.add(file);
		else
			for (File f : file.listFiles())
				addFileToArray(f);
	}
	
	private static Random rnd = new Random();
	
	private static File getRandomFile() throws FileNotFoundException {
		initArray();
		
		File f = files.get(rnd.nextInt(files.size()));
		logger.info("Selected file: {}", f.toString());
		return f;
	}
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		RequestDispatcher disp = request.getRequestDispatcher("/getPage?path=" + GetPageServlet.getRelativePath(getRandomFile()));
		disp.forward(request, response);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

}
