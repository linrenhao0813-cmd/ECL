package com.ecl.modrinth.ui;

record ModCategoryChoice(String label, String id) {
    @Override
    public String toString() {
        return label;
    }
}
