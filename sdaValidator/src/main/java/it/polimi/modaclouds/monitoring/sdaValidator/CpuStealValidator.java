package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class CpuStealValidator {

	public static void main(String[] args) {
		Scanner input;
		List<String> highValues=new ArrayList<String>();
		int window=83;
		float partialSum=0;
		int count=0;
		
			try {
				input = new Scanner(new File("stolen.out"));
				
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
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			for(String high: highValues){
				System.out.println(high);
			}
	}

}
