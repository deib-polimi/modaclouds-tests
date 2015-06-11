package it.polimi.modaclouds.sdatests.validator.util;

public class Workload {

	private int timestep;
	
	private float value;

	public Workload(int timestep, float value){
		this.timestep=timestep;
		this.value=value;
	}
	
	public int getTimestep() {
		return timestep;
	}

	public void setTimestep(int timestep) {
		this.timestep = timestep;
	}

	public float getValue() {
		return value;
	}

	public void setValue(float value) {
		this.value = value;
	}
	

}
