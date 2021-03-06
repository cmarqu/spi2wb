SBT=sbt

hdl:
	$(SBT) "runMain spi2wb.Spi2Wb$(DATASIZE)"

test:
	cd cocotb/; DATASIZE=$(DATASIZE) make

scalatest:
	$(SBT) "test:testOnly spi2wb.TestSpi2Wb"

publishlocal:
	$(SBT) publishLocal

mrproper:
	make -C cocotb/ mrproper
	-rm *.anno.json
	-rm *.fir
	-rm *.v
	-rm -rf target
	-rm -rf test_run_dir
	-rm -rf project
