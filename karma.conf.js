module.exports = function(config){
	config.set({
		reporters: ['mocha'],
		plugins: ['karma-mocha-reporter']
	});
}
