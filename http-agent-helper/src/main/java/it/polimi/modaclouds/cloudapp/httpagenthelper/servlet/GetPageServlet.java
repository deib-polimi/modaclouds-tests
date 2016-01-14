package it.polimi.modaclouds.cloudapp.httpagenthelper.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.polimi.tower4clouds.java_app_dc.Monitor;

/**
 * Servlet implementation class GetPageServlet
 */
@WebServlet("/getPage")
public class GetPageServlet extends HttpServlet {

	private static final long serialVersionUID = -3866752859346288461L;
	
	private static final Logger logger = LoggerFactory.getLogger(GetPageServlet.class);
	
	public static String BASE_PATH = "/home/vagrant/modaclouds-tests/http-agent-helper/src/main/java/it/polimi/modaclouds/cloudapp/httpagenthelper/servlet"; //"/sync/model/model/CaseStudyModel/";
	
	public static final String BASE_PATH_PROPERTY = "HTTP_AGENT_BASE_PATH";
	
	public static void loadFromEnrivonmentVariables() {
		String path = System.getenv(BASE_PATH_PROPERTY);
		
		if (path != null)
			BASE_PATH = path;
	}
	
	public static void loadFromSystemProperties() {
		String path = System.getProperty(BASE_PATH_PROPERTY);
		
		if (path != null)
			BASE_PATH = path;
	}
	
	static {
		loadFromSystemProperties();
		loadFromEnrivonmentVariables();
	}
	
	public static String ls(String path) {
		path = cleanPath(path);
		
		if (path == null || path.trim().length() == 0)
			path = "";
		
		StringBuilder sb = new StringBuilder();
		
		Path p = Paths.get(BASE_PATH, path);
		
		sb.append("<h1>ls for ");
		if (path.equals(""))
			sb.append("the home");
		else
			sb.append(path);
		
		sb.append("</h1>\n<ul>\n");
		
		if (!path.equals(""))
			sb.append("\t<li>" + getHTMLLink(p.toFile().getParentFile(), "..") + "</li>\n");
		
		if (p.toFile().isDirectory()) {
			File dir = p.toFile();
			for (File f : dir.listFiles())
				sb.append("\t<li>" + getHTMLLink(f, f.getName()) + "</li>\n");
		} else {
			sb.append("\t<li>" + getHTMLLink(p.toFile(), p.toFile().getName()) + "</li>\n");
		}
		
		sb.append("</ul>");
		
		return sb.toString();
	}
	
	private static String getHTMLLink(File f, String name) {
		if (!f.exists())
			return getRelativePath(f);
		
		StringBuilder sb = new StringBuilder();
		sb.append("<a href=\"");
		
		if (f.isDirectory())
			sb.append("index.jsp?path=" + getRelativePath(f));
		else
			sb.append("getPage?path=" + getRelativePath(f));
		
		sb.append("\">" + name + "</a>");
		
		return sb.toString();
	}
	
	public static String getRelativePath(File f) {
		String path = f.getAbsolutePath();
		return path.substring(path.indexOf(BASE_PATH) + BASE_PATH.length());
	}
	
	private static String cleanPath(String path) {
		if (path == null)
			return null;
		
		path = path.replaceAll("[.][.]", "");
		
		while (path.indexOf(File.separator + File.separator) > -1)
			path = path.replaceAll(File.separator + File.separator, File.separator);
		
		return path;
	}
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	@Monitor(type = "getPage")
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String path = cleanPath(request.getParameter("path"));
		
		if (path == null || path.trim().length() == 0)
			return;
		
		logger.info("Trying opening the file {}{}...", BASE_PATH, path);
		
		Path p = Paths.get(BASE_PATH, path);
		
		if (!p.toFile().exists())
			throw new FileNotFoundException();
		
		response.setContentType(Files.probeContentType(p));
		response.addHeader("Content-Disposition", "attachment; filename=" + p.toFile().getName());
		response.setContentLength((int) p.toFile().length());

		try (FileInputStream fileInputStream = new FileInputStream(p.toFile())) {
			OutputStream responseOutputStream = response.getOutputStream();
			int bytes;
			while ((bytes = fileInputStream.read()) != -1)
				responseOutputStream.write(bytes);
		}
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

}
