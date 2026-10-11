package com.sunnatbek.villagerciv.persistence;

/** A save file exists but its content cannot be understood. */
public final class SaveFormatException extends RuntimeException {
	public SaveFormatException(String message) {
		super(message);
	}
}
