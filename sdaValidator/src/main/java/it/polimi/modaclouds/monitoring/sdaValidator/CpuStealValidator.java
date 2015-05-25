package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class CpuStealValidator {
	
	public static final String STOLEN = "stolen.out";
	
	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static void perform(Path parent) {
		Path stolen = Paths.get(parent.toString(), STOLEN);
		if (stolen == null || !stolen.toFile().exists())
			throw new RuntimeException("CPU stolen file not found or wrong path ("
					+ stolen.toString() + ")");
		
		Scanner input;
		List<String> highValues=new ArrayList<String>();
		int window=83;
		float partialSum=0;
		int count=0;
		
			try {
				input = new Scanner(stolen);
				
				while(input.hasNextLine())
				{
				   String line = input.nextLine();
				   String[] splitted=line.split(",");
				   Float value=Float.parseFloat(splitted[3]);
				   if(value.floatValue()>0.09){
					   highValues.add(line);
				   }
				   
				   if(count<window){
					   count++;
					   partialSum=partialSum+value.floatValue();
				   }else{
					   if(partialSum>0)
						   System.out.println(partialSum/window);
					   else
						   System.out.println(0);
					   count=0;
					   partialSum=0;
				   }
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			for(String high: highValues){
				System.out.println(high);
			}
	}

}
