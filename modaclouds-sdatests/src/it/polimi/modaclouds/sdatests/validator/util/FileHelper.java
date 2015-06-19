package it.polimi.modaclouds.sdatests.validator.util;

import it.polimi.modaclouds.sdatests.validator.util.Datum.Type;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FileHelper {
	
	private static final Logger logger = LoggerFactory.getLogger(FileHelper.class);
	
	public static void rewriteJsonAsCsv(Path file) {
		if (!file.toFile().exists())
			throw new RuntimeException("The file doesn't exists!");
		
		try {
			Type origDataType = Datum.getTypeFromFile(file);
			if (origDataType == Type.CSV)
				return;
			
			Path p = Files.createTempFile("file", ".out");
			jsonToCsv(file, p, origDataType);
			Files.move(file, Paths.get(file.toFile().getParent(), file.toFile().getName() + "-bak"), StandardCopyOption.REPLACE_EXISTING);
			Files.move(p, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			logger.error("Error while rewrite the file JSON as a CSV.", e);
		}
	}
	
	public static void jsonToCsv(Path file, Path newFile, Datum.Type origDataType) {
		if (origDataType == Type.CSV)
			throw new RuntimeException("The file is already CSV!");
		
		try {
			List<Datum> data = Datum.getAllData(file, origDataType);
			
			try (PrintWriter out = new PrintWriter(newFile.toFile())) {
				out.println(Datum.getCSVHeader());
				for (Datum d : data)
					out.println(d.toCSV());
			}
			
		} catch (Exception e) {
			logger.error("Error while converting the file in CSV.", e);
		}
	}

}
